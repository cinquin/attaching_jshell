/*
 * Copyright (c) 2017, Olivier Cinquin
 */

package uk.org.cinquin.attaching_jshell;

import static jdk.jshell.execution.JdiExecutionControlProvider.PARAM_HOST_NAME;
import static jdk.jshell.execution.JdiExecutionControlProvider.PARAM_REMOTE_AGENT;
import static jdk.jshell.execution.JdiExecutionControlProvider.PARAM_TIMEOUT;

import java.util.HashMap;
import java.util.Map;

import jdk.jshell.spi.ExecutionControl;
import jdk.jshell.spi.ExecutionControlProvider;
import jdk.jshell.spi.ExecutionEnv;

/**
 * This provider is detected at runtime by {@link jdk.jshell.spi.ExecutionControl}::generate.
 * It needs to be advertised as a service in a META-INF/services directory to be made
 * available.
 *
 * Created by olivier on 4/29/17.
 */
public class AttachToExistingVMProvider implements ExecutionControlProvider {
	@Override
	public String name() {
		return "attachToExistingVM";
	}

	public String PARAM_PORT = "port";

	public Map<String,String> defaultParameters() {
		Map<String, String> result = new HashMap<>();
		result.put(PARAM_HOST_NAME, "localhost");
		result.put(PARAM_PORT, "4568");
		result.put(PARAM_TIMEOUT, "30000");
		return result;
	}

	@Override
	public ExecutionControl generate(ExecutionEnv env, Map<String, String> parameters) throws Throwable {
		Map<String, String> dp  = defaultParameters();
		if (parameters == null) {
			parameters = dp;
		}
		String remoteAgent = parameters.getOrDefault(PARAM_REMOTE_AGENT, dp.get(PARAM_REMOTE_AGENT));
		int timeout = Integer.parseUnsignedInt(parameters.getOrDefault(PARAM_TIMEOUT, dp.get(PARAM_TIMEOUT)));
		String host = parameters.getOrDefault(PARAM_HOST_NAME, dp.get(PARAM_HOST_NAME));
		int port = Integer.valueOf(parameters.getOrDefault(PARAM_PORT, dp.get(PARAM_PORT)));
		return ExistingVMJdi.create(env, remoteAgent, host, port, timeout);
	}
}
