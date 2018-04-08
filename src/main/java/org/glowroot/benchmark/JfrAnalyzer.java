package org.glowroot.benchmark;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
		File jfrFile = new File(args[0]);
		IItemCollection collection = JfrLoaderToolkit.loadEvents(jfrFile);
		for (IItemIterable items : collection.apply(ItemFilters.type(JdkTypeIDs.EXECUTION_SAMPLE))) {
			IMemberAccessor<IMCStackTrace, IItem> accessor = JfrAttributes.EVENT_STACKTRACE
					.getAccessor(items.getType());
			for (IItem item : items) {
				totalSamples++;
				processStackTrace(accessor.getMember(item));
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

	private static void printNode(Node node, int indent) {
		for (int i = 0; i < indent; i++) {
			System.out.print("  ");
		}
		System.out.format("%3d %s%n", node.count, node.frame);
		for (Node childNode : node.getOrderedChildNodes()) {
			printNode(childNode, indent + 1);
		}
	}

	private static void processStackTrace(IMCStackTrace stackTrace) {
		boolean analyze = false;
		int analyzeFromIndex = 0;
		List<? extends IMCFrame> frames = stackTrace.getFrames();
		for (int i = frames.size() - 1; i >= 0; i--) {
			IMCFrame frame = frames.get(i);
			IMCMethod method = frame.getMethod();
			if (method.getType().getPackageName().startsWith("org.glowroot")
					&& !method.getType().getPackageName().startsWith("org.glowroot.benchmark")) {
				analyze = true;
				analyzeFromIndex = Math.min(i + 2, frames.size() - 1);
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
