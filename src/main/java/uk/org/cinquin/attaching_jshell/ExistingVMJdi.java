/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 * Copyright (c) 2017, Olivier Cinquin
 */

package uk.org.cinquin.attaching_jshell;

import static jdk.jshell.execution.Util.remoteInputOutput;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import com.sun.jdi.BooleanValue;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.ClassNotLoadedException;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.InvalidTypeException;
import com.sun.jdi.InvocationException;
import com.sun.jdi.Location;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventIterator;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.BreakpointRequest;

import jdk.jshell.execution.JdiExecutionControl;
import jdk.jshell.execution.Util;
import jdk.jshell.spi.ExecutionControl;
import jdk.jshell.spi.ExecutionEnv;

/**
 * This class is derived from {@link jdk.jshell.execution.JdiDefaultExecutionControl}.
 * Instead of launching a new VM in which to run snippets, it attaches to an already-running
 * VM, identified using the hostname and port parameters. The attached VM is kept alive
 * when the execution engine is closed.
 *
 * Ideally this class would extend {@link jdk.jshell.execution.JdiDefaultExecutionControl} and
 * thus avoid code duplication, but that did not seem possible given limited visibility of
 * members of that class.
 *
 * Created by olivier on 4/29/17.
 */
public class ExistingVMJdi extends JdiExecutionControl {


	private VirtualMachine vm;
	private final String remoteAgent;

	private final Object STOP_LOCK = new Object();
	private boolean userCodeRunning = false;

	private static Connector findConnector(String name) {
		for (Connector cntor
			: Bootstrap.virtualMachineManager().allConnectors()) {
			if (cntor.name().equals(name)) {
				return cntor;
			}
		}
		return null;
	}

	/*public static void main(String [] args) throws Exception {
		create(null, "", "localhost", 4568, 30_000);
	}*/

	/**
	 * Creates an ExecutionControl instance based on a JDI
	 * {@code ListeningConnector} or {@code LaunchingConnector}.
	 *
	 * Initialize JDI and use it to launch the remote JVM. Set-up a socket for
	 * commands and results. This socket also transports the user
	 * input/output/error.
	 *
	 * @param env the context passed by
	 * {@brokenlink jdk.jshell.spi.ExecutionControl#start(jdk.jshell.spi.ExecutionEnv) }
	 * @param remoteAgent the remote agent to launch
	 * @param host explicit hostname to use, if null use discovered
	 * hostname, applies to listening only (!isLaunch)
	 * @return the channel
	 * @throws IOException if there are errors in set-up
	 */
	static ExecutionControl create(ExecutionEnv env, String remoteAgent,
								   String host, int debuggingPort, int millsTimeout) throws IOException {
		try (final ServerSocket listener = new ServerSocket(0, 1)) {
			// timeout on I/O-socket
			listener.setSoTimeout(millsTimeout);
			int port = listener.getLocalPort();

			Connector connector = findConnector("com.sun.jdi.SocketAttach");
			AttachingConnector attacher = (AttachingConnector) connector;
			Map<String, Connector.Argument> args = attacher.defaultArguments();
			args.get("hostname").setValue(host);
			args.get("port").setValue(Integer.toString(debuggingPort));
			final VirtualMachine vm;
			try {
				vm = attacher.attach(args);
			} catch (IllegalConnectorArgumentsException e) {
				throw new RuntimeException(e);
			}
			ThreadReference reservedThread = null;
			for (ThreadReference t: vm.allThreads()) {
				if (t.name().startsWith("ExistingVMRemoteExecutionControl")) {
					reservedThread = t;
					break;
				}
			}

			if (reservedThread == null) {
				throw new IllegalStateException("Remote VM does not have the expected thread");
			}

			List<ReferenceType> classes = vm.classesByName(
				"uk.org.cinquin.attaching_jshell.ExistingVMRemoteExecutionControl");
			if (classes.isEmpty()) {
				throw new IllegalStateException(
					"Remote VM does not have class uk.org.cinquin.attaching_jshell.ExistingVMRemoteExecutionControl");
			}
			List<Location> breakpointLocations = new ArrayList<>();
			classes.forEach(c -> c.methodsByName("breakpointMethod").forEach(
				method -> breakpointLocations.add(method.location())));

			ThreadReference reservedThread0 = reservedThread;
			breakpointLocations.forEach(loc -> {
				BreakpointRequest br = vm.eventRequestManager().createBreakpointRequest(loc);
				br.addThreadFilter(reservedThread0);
				br.enable();
			});

			outer:
			while (true) {
				try {
					EventSet es = vm.eventQueue().remove(10_000);
					if (es == null) {
						throw new TimeoutException();
					}
					EventIterator it = es.eventIterator();
					while (it.hasNext()) {
						Event e = it.nextEvent();
						if (e instanceof BreakpointEvent) {
							BreakpointEvent bre = (BreakpointEvent) e;
							if (breakpointLocations.contains(bre.location())) {
								break outer;
							}
							System.err.println("Hit a breakpoint not set by us: " + bre.location());
						} else {
							System.err.println("Unknown event: " + e);
						}
					}
				} catch (InterruptedException | TimeoutException e) {
					throw new RuntimeException("Remote VM did not get to breakpoint after at least 10 s");
				}
			}

			ReferenceType classRef = classes.get(0);
			if (!(classRef instanceof ClassType)) {
				throw new RuntimeException("Reference " + classRef + " is not a ClassType");
			}

			StringReference mainArg = vm.mirrorOf(InetAddress.getLocalHost().getHostName() + ":" + port);
			String baseExceptionMessage = " while invoking ExistingVMRemoteExecutionControl::main0 in remote VM";
			try {
				((ClassType) classRef).invokeMethod(reservedThread0, classRef.methodsByName("main0").get(0),
					Arrays.asList(mainArg), 0);
			} catch (InvalidTypeException | ClassNotLoadedException | IncompatibleThreadStateException |
					InvocationException e) {
				throw new RuntimeException("Exception" + baseExceptionMessage, e);
			} catch (Exception e) {//e.g. if methodsByName does not find main0
				throw new RuntimeException(
					"Unexpected exception" + baseExceptionMessage, e);
			} finally {
				vm.eventRequestManager().deleteAllBreakpoints();
				try {
					EventSet eventSet;
					do {
						eventSet = vm.eventQueue().remove(1);
					} while (eventSet != null);
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
				vm.resume();
			}

			List < Consumer < String >> deathListeners = new ArrayList<>();
			Util.detectJdiExitEvent(vm, s -> {
				for (Consumer<String> h : deathListeners) {
					h.accept(s);
				}
			});

			// Set-up the commands/results on the socket.  Piggy-back snippet
			// output.
			Socket socket = listener.accept();
			// out before in -- match remote creation so we don't hang
			OutputStream out = socket.getOutputStream();
			Map<String, OutputStream> outputs = new HashMap<>();
			outputs.put("out", env.userOut());
			outputs.put("err", env.userErr());
			Map<String, InputStream> input = new HashMap<>();
			input.put("in", env.userIn());
			return remoteInputOutput(socket.getInputStream(), out, outputs, input,
				(objIn, objOut) -> new ExistingVMJdi(env,
					objOut, objIn, vm, remoteAgent, deathListeners));
		}
	}

	/**
	 * Create an instance.
	 *
	 * @param cmdout the output for commands
	 * @param cmdin the input for responses
	 */
	private ExistingVMJdi(ExecutionEnv env,
									   ObjectOutput cmdout, ObjectInput cmdin,
									   VirtualMachine vm, String remoteAgent,
									   List<Consumer<String>> deathListeners) {
		super(cmdout, cmdin);
		this.vm = vm;
		this.remoteAgent = remoteAgent;
		// We have now succeeded in establishing the connection.
		// If there is an exit now it propagates all the way up
		// and the VM should be disposed of.
		deathListeners.add(s -> env.closeDown());
	}

	@Override
	public String invoke(String classname, String methodname)
		throws RunException,
		EngineTerminationException, InternalException {
		String res;
		synchronized (STOP_LOCK) {
			userCodeRunning = true;
		}
		try {
			res = super.invoke(classname, methodname);
		} finally {
			synchronized (STOP_LOCK) {
				userCodeRunning = false;
			}
		}
		return res;
	}

	/**
	 * Interrupts a running remote invoke by manipulating remote variables
	 * and sending a stop via JDI.
	 *
	 * @throws EngineTerminationException the execution engine has terminated
	 * @throws InternalException an internal problem occurred
	 */
	@Override
	public void stop() throws EngineTerminationException, InternalException {
		synchronized (STOP_LOCK) {
			if (!userCodeRunning) {
				return;
			}

			vm().suspend();
			try {
				OUTER:
				for (ThreadReference thread : vm().allThreads()) {
					// could also tag the thread (e.g. using name), to find it easier
					for (StackFrame frame : thread.frames()) {
						if (remoteAgent.equals(frame.location().declaringType().name()) &&
							(    "invoke".equals(frame.location().method().name())
								|| "varValue".equals(frame.location().method().name()))) {
							ObjectReference thiz = frame.thisObject();
							Field inClientCode = thiz.referenceType().fieldByName("inClientCode");
							Field expectingStop = thiz.referenceType().fieldByName("expectingStop");
							Field stopException = thiz.referenceType().fieldByName("stopException");
							if (((BooleanValue) thiz.getValue(inClientCode)).value()) {
								thiz.setValue(expectingStop, vm().mirrorOf(true));
								ObjectReference stopInstance = (ObjectReference) thiz.getValue(stopException);

								vm().resume();
								debug("Attempting to stop the client code...\n");
								thread.stop(stopInstance);
								thiz.setValue(expectingStop, vm().mirrorOf(false));
							}

							break OUTER;
						}
					}
				}
			} catch (ClassNotLoadedException | IncompatibleThreadStateException | InvalidTypeException ex) {
				throw new InternalException("Exception on remote stop: " + ex);
			} finally {
				vm().resume();
			}
		}
	}

	@Override
	public void close() {
		super.close();
		vm.dispose();
	}

	@Override
	protected synchronized VirtualMachine vm() throws EngineTerminationException {
		if (vm == null) {
			throw new EngineTerminationException("VM closed");
		} else {
			return vm;
		}
	}

	/**
	 * Log debugging information. Arguments as for {@code printf}.
	 *
	 * @param format a format string as described in Format string syntax
	 * @param args arguments referenced by the format specifiers in the format
	 * string.
	 */
	private static void debug(String format, Object... args) {
		System.err.printf(format, args);
	}

	/**
	 * Log a serious unexpected internal exception.
	 *
	 * @param ex the exception
	 * @param where a description of the context of the exception
	 */
	private static void debug(Throwable ex, String where) {
		ex.printStackTrace(System.err);
	}
}
