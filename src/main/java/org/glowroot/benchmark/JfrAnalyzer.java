package org.glowroot.benchmark;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.oracle.jmc.common.IMCFrame;
import com.oracle.jmc.common.IMCMethod;
import com.oracle.jmc.common.IMCStackTrace;
import com.oracle.jmc.common.IMemberAccessor;
import com.oracle.jmc.common.item.IItem;
import com.oracle.jmc.common.item.IItemCollection;
import com.oracle.jmc.common.item.IItemIterable;
import com.oracle.jmc.common.item.ItemFilters;
import com.oracle.jmc.flightrecorder.JfrAttributes;
import com.oracle.jmc.flightrecorder.JfrLoaderToolkit;
import com.oracle.jmc.flightrecorder.jdk.JdkTypeIDs;

public class JfrAnalyzer {

	private static final boolean INCLUDE_LINE_NUMBER = true;

	// this is useful when analyzing startup overhead
	private static final boolean EXCLUDE_LOAD_CLASS = false;

	private static final Node syntheticRootNode = new Node("");
	private static int totalSamples = 0;

	public static void main(String... args) throws Exception {
		args = new String[] { "profb.jfr" };
		File jfrFile = new File(args[0]);
		IItemCollection collection = JfrLoaderToolkit.loadEvents(jfrFile);

		Set<String> glowrootCallers = getGlowrootCallers(collection);
		// if (true)
		// return;
		// for (String s : glowrootCallers) {
		// System.out.println(s);
		// }

		for (IItemIterable items : collection.apply(ItemFilters.type(JdkTypeIDs.EXECUTION_SAMPLE))) {
			IMemberAccessor<IMCStackTrace, IItem> accessor = JfrAttributes.EVENT_STACKTRACE
					.getAccessor(items.getType());
			for (IItem item : items) {
				totalSamples++;
				processStackTrace(accessor.getMember(item), glowrootCallers);
			}
		}

		int totalGlowrootSamples = 0;
		for (Node rootNode : syntheticRootNode.getOrderedChildNodes()) {
			totalGlowrootSamples += rootNode.count;
		}

		System.out.println("Total Samples: " + totalSamples);
		System.out.print("Total Glowroot: " + totalGlowrootSamples);
		System.out.format(" (%.2f%%)%n", 100 * totalGlowrootSamples / (double) totalSamples);
		System.out.println();
		for (Node rootNode : syntheticRootNode.getOrderedChildNodes()) {
			printNode(rootNode, 0);
		}
	}

	private static Set<String> getGlowrootCallers(IItemCollection collection) {
		Set<String> glowrootCallers = new HashSet<>();
		for (IItemIterable items : collection.apply(ItemFilters.type(JdkTypeIDs.EXECUTION_SAMPLE))) {
			IMemberAccessor<IMCStackTrace, IItem> accessor = JfrAttributes.EVENT_STACKTRACE
					.getAccessor(items.getType());
			for (IItem item : items) {
				String glowrootCaller = getGlowrootCaller(accessor.getMember(item));
				if (glowrootCaller != null) {
					glowrootCallers.add(glowrootCaller);
				}
			}
		}
		return glowrootCallers;
	}

	private static String getGlowrootCaller(IMCStackTrace stackTrace) {
		List<? extends IMCFrame> frames = stackTrace.getFrames();
		for (int i = frames.size() - 1; i >= 0; i--) {
			IMCFrame frame = frames.get(i);
			IMCMethod method = frame.getMethod();
			if (method.getType().getPackageName().startsWith("org.glowroot.agent")
					&& !method.getMethodName().equals("run")) {
				frame = frames.get(i + 1);
				method = frame.getMethod();
				String stackTraceElement = getStackTraceElement(method, frame);
				if (stackTraceElement.equals("jdk.internal.reflect.DelegatingMethodAccessorImpl.invoke() line: 43")) {
					continue;
					// for (int j = i; j < frames.size(); j++) {
					// frame = frames.get(j);
					// method = frame.getMethod();
					// System.out.println(getStackTraceElement(method, frame));
					// }
					// System.out.println();
				}
				return stackTraceElement;
			}
		}
		return null;
	}

	private static void printNode(Node node, int indent) {
		for (int i = 0; i < indent; i++) {
			System.out.print("  ");
		}
		System.out.format("%3d %s%n", node.count, node.frame);
		for (Node childNode : node.getOrderedChildNodes()) {
			printNode(childNode, indent + 1);
		}
	}

	private static void processStackTrace(IMCStackTrace stackTrace, Set<String> glowrootCallers) {
		boolean analyze = false;
		int analyzeFromIndex = 0;
		List<? extends IMCFrame> frames = stackTrace.getFrames();
		for (int i = frames.size() - 1; i >= 0; i--) {
			IMCFrame frame = frames.get(i);
			IMCMethod method = frame.getMethod();
			String stackTraceElement = getStackTraceElement(method, frame);
			if (glowrootCallers.contains(stackTraceElement)) {
				if (i == 0) {
					analyze = true;
					analyzeFromIndex = i;
					break;
				}
				String nextClassName = frames.get(i - 1).getMethod().getType().getFullName();
				if (nextClassName.startsWith("java.") || nextClassName.startsWith("org.glowroot")) {
					analyze = true;
					analyzeFromIndex = i + 2;
					break;
				}
			}
			if (method.getType().getPackageName().startsWith("org.glowroot.agent")) {
				analyze = true;
				analyzeFromIndex = Math.min(i + 1, frames.size() - 1);
				break;
			}
		}
		if (!analyze) {
			return;
		}
		if (EXCLUDE_LOAD_CLASS) {
			for (int i = analyzeFromIndex; i >= 0; i--) {
				IMCFrame frame = frames.get(i);
				IMCMethod method = frame.getMethod();
				if (method.getMethodName().equals("loadClass")) {
					return;
				}
			}
		}
		Node node = syntheticRootNode;
		for (int i = analyzeFromIndex; i >= 0; i--) {
			IMCFrame frame = frames.get(i);
			IMCMethod method = frame.getMethod();
			String stackTraceElement = getStackTraceElement(method, frame);
			node = node.recordChildSample(stackTraceElement);
		}
	}

	private static String getStackTraceElement(IMCMethod method, IMCFrame frame) {
		String s = method.getType().getFullName() + "." + method.getMethodName() + "()";
		if (INCLUDE_LINE_NUMBER) {
			s += " line: " + frame.getFrameLineNumber();
		}
		return s;
	}

	private static class Node {

		private final String frame;
		private final Map<String, Node> childNodes = new HashMap<String, Node>();
		private int count;

		private Node(String frame) {
			this.frame = frame;
		}

		private Node recordChildSample(String stackTraceElement) {
			Node childNode = childNodes.get(stackTraceElement);
			if (childNode == null) {
				childNode = new Node(stackTraceElement);
				childNodes.put(stackTraceElement, childNode);
			}
			childNode.count++;
			return childNode;
		}

		private List<Node> getOrderedChildNodes() {
			List<Node> nodes = new ArrayList<Node>(childNodes.values());
			Collections.sort(nodes, new Comparator<Node>() {
				@Override
				public int compare(Node node1, Node node2) {
					return node2.count - node1.count;
				}
			});
			return nodes;
		}
	}
}
