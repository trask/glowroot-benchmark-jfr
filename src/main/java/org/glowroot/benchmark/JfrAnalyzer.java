package org.glowroot.benchmark;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.jrockit.mc.common.IMCFrame;
import com.jrockit.mc.common.IMCMethod;
import com.jrockit.mc.common.IMCStackTrace;
import com.jrockit.mc.flightrecorder.FlightRecording;
import com.jrockit.mc.flightrecorder.FlightRecordingLoader;
import com.jrockit.mc.flightrecorder.spi.IEvent;
import com.jrockit.mc.flightrecorder.spi.IField;

public class JfrAnalyzer {

    private static final boolean INCLUDE_LINE_NUMBER = true;

    private static final Node syntheticRootNode = new Node("");
    private static int totalSamples = 0;

    public static void main(String... args) {
        File jfrFile = new File(args[0]);
        FlightRecording recording = FlightRecordingLoader.loadFile(jfrFile);
        for (IEvent event : recording.createView()) {
            if (event.getEventType().getName().equals("Method Profiling Sample")) {
                totalSamples++;
                processStackTrace(getStackTrace(event));
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

    private static IMCStackTrace getStackTrace(IEvent event) {
        for (IField field : event.getEventType().getFields()) {
            if (field.getName().equals("Stack Trace")) {
                return (IMCStackTrace) field.getValue(event);
            }
        }
        throw new AssertionError();
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
            if (method.getPackageName().startsWith("org.glowroot")
                    && !method.getPackageName().startsWith("org.glowroot.benchmark")) {
                analyze = true;
                analyzeFromIndex = i;
                if (method.getClassName().equals("AdviceFlowOuterHolder")) {
                    analyzeFromIndex = i + 1;
                }
                break;
            }
            if (method.getMethodName().contains("$glowroot$") && i == 0) {
                analyze = true;
                analyzeFromIndex = i;
                break;
            }
        }
        if (!analyze) {
            return;
        }
        // further filter out based on some criteria
        for (int i = analyzeFromIndex; i >= 0; i--) {
            IMCFrame frame = frames.get(i);
            IMCMethod method = frame.getMethod();
            if (method.getMethodName().equals("loadClass")) {
                return;
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
        String s = method.getPackageName() + "." + method.getClassName() + "." +
                method.getMethodName() + "()";
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
