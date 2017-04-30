/*
 * Copyright (c) 2017, Olivier Cinquin
 *
 * "main" method Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 */

package uk.org.cinquin.attaching_jshell;

import static jdk.jshell.execution.Util.forwardExecutionControlAndIO;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import jdk.jshell.execution.RemoteExecutionControl;

/**
 * A member of this class must have been pre-instantied in the target VM to which JShell attaches.
 * A polling thread is created to allow the JShell engine provider to set up a link over JDI.
 * Note that the target VM's stderr (and stdout?) are redirected upon connection, and are
 * currently not restored when JShell detaches.
 *
 * NB: a static version, which does not require any instantiation, would probably also work.
 *
 * Created by olivier on 4/29/17.
 */
public class ExistingVMRemoteExecutionControl extends RemoteExecutionControl {

	/**
	 * Launch the agent, connecting to the JShell-core over the socket specified
	 * in the command-line argument.
	 *
	 * This method is adapted from the corresponding method in the base JDK class.
	 *
	 * @param args hostname:port
	 * @throws Exception any unexpected exception
	 */
	public static void main(String args) throws IOException {
		String[] split = args.split(":");
		Socket socket = new Socket(split[0], Integer.parseInt(split[1]));
		InputStream inStream = socket.getInputStream();
		OutputStream outStream = socket.getOutputStream();
		Map<String, Consumer<OutputStream>> outputs = new HashMap<>();
		outputs.put("out", st -> System.setOut(new PrintStream(st, true)));
		outputs.put("err", st -> System.setErr(new PrintStream(st, true)));
		Map<String, Consumer<InputStream>> input = new HashMap<>();
		input.put("in", System::setIn);
		forwardExecutionControlAndIO(new RemoteExecutionControl(), inStream, outStream, outputs, input);
	}

	public static void main0(String args) {
		//Call main from a new thread so that main0 invocation can return,
		//which avoids a deadlock
		Thread thread = new Thread(() -> {
			try {
				main(args);
			} catch (Throwable t) {
				t.printStackTrace();
			}
		});
		thread.setName("JShell thread " + threadCounter);
		thread.start();
	}

	static final AtomicInteger threadCounter = new AtomicInteger(0);

	private void breakpointMethod() {
		//Intentionally empty; used as a jumping point to establish the JShell connection,
		//by having the JShell execution engine invoke main0 using JDI when the breakpoint
		//for this method is hit.
	}

	private void loopWaitingForJDIAttach() {
		while(true) {
			try {
				breakpointMethod();
				Thread.sleep(500);
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
			breakpointMethod();
		}
	}

	public static String theGoodsForTesting = "ARE HERE";

	public ExistingVMRemoteExecutionControl() {
		Thread t = new Thread(this::loopWaitingForJDIAttach);
		t.setName("ExistingVMRemoteExecutionControl");
		t.start();

		//heartBeat thread for testing purposes
		Thread heartBeat = new Thread(() -> {
			while (true) {
				System.err.println("alive");
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		});
		heartBeat.setName("Heartbeat thread");
		//heartBeat.start();
	}

	/**
	 * For testing purposes.
	 */
	public static void main(String[] args) {
		new ExistingVMRemoteExecutionControl();
	}
}
