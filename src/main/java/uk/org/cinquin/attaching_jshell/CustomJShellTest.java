/*
 * Copyright (c) 2017, Olivier Cinquin
 */

package uk.org.cinquin.attaching_jshell;

import static jdk.jshell.execution.JdiExecutionControlProvider.PARAM_HOST_NAME;
import static jdk.jshell.execution.JdiExecutionControlProvider.PARAM_TIMEOUT;

import java.util.HashMap;
import java.util.Map;

import jdk.jshell.JShell;

/**
 * Test class to programmatically invoke JShell in a way that it attaches to an
 * already-existing VM.
 * Created by olivier on 4/29/17.
 */
public class CustomJShellTest {

	public static void main(String[] args) throws InterruptedException {
		JShell.Builder builder = JShell.builder();
		Map<String, String> params = new HashMap<>();
		params.put(PARAM_HOST_NAME, "localhost");
		params.put(PARAM_TIMEOUT, "10000");
		builder.executionEngine(new AttachToExistingVMProvider(), params);
		JShell shell = builder.build();
		shell.eval("int k = 3 + 15;").forEach(sne -> System.out.println(sne.toString()));
		shell.eval("import uk.org.cinquin.attaching_jshell.ExistingVMRemoteExecutionControl;").forEach(sne -> System.out.println(sne.toString()));
		shell.eval("String s = ExistingVMRemoteExecutionControl.theGoodsForTesting;").forEach(sne -> System.out.println(sne.toString()));
		Thread.sleep(10_000);
		shell.close();
	}

}
