/*
 * Copyright (c) 2023, BELLSOFT. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test TestHotCodeHeap
 * @library /test/lib
 * @run testng/othervm TestHotCodeHeap
 * @requires vm.flavor == "server"
 * @summary Test of Hot CodeCache segment
 */

import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.util.Iterator;
import java.util.ArrayList;
import jdk.test.lib.dcmd.JMXExecutor;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import org.testng.annotations.Test;

// Test runs a TestHotCodeHeap.main() in a separate VM. A separate VM runs the
// Compiler.codecache and Compiler.codelist jcmd commands. The output of these
// commands is parsed in a parent process: raw nmethod addresses are compared
// to CodeHeap segment ranges to find a segment where the method was installed.
// The child VM is started with different options to check where its methods are located.
// The goal is to test CompilerDirectives and CompilerControl: all given nmethods
// are in extrahot, none are is in any other segment.

public class TestHotCodeHeap {

    class CodeHeap {
        final static String SEGMENT_HOT = "extra-hot";
        final static String SEGMENT_NONPROF = "non-profiled nmethods";
        final static String SEGMENT_PROFILED = "profiled nmethods";
        final static String SEGMENT_NONNMETHOD = "non-nmethods";

        long hotSegmentStart = 0;
        long hotSegmentEnd = 0;
        long c1SegmentStart = 0;
        long c1SegmentEnd = 0;
        long c2SegmentStart = 0;
        long c2SegmentEnd = 0;
        long nonmethodSegmentStart = 0;
        long nonmethodSegmentEnd = 0;

        ArrayList<String> hotSegmentMethods = new ArrayList<String>();
        ArrayList<String> c1SegmentMethods = new ArrayList<String>();
        ArrayList<String> c2SegmentMethods = new ArrayList<String>();
        ArrayList<String> nonnmethodSegmentMethods = new ArrayList<String>();

        void addSegment(String name, long start, long end) {
            if (SEGMENT_HOT.equals(name)) {
                hotSegmentStart = start;
                hotSegmentEnd = end;
            } else
            if (SEGMENT_NONPROF.equals(name)) {
                c1SegmentStart = start;
                c1SegmentEnd = end;
            } else
            if (SEGMENT_PROFILED.equals(name)) {
                c2SegmentStart = start;
                c2SegmentEnd = end;
            } else
            if (SEGMENT_NONNMETHOD.equals(name)) {
                nonmethodSegmentStart = start;
                nonmethodSegmentEnd = end;
            } else
            // -XX:-SegmentedCodeCache case: DCMD.Codecache reports no segment name, just "CodeCache: size=.."
            if ("CodeCache".equals(name)) {
                // let us put all the CodeCache stuff into c1 Segment
                c1SegmentStart = start;
                c1SegmentEnd = end;
            } else
            // -XX:-SegmentedCodeCache -XX:+ExtraHotCodeCache case: DCMD reports "ExtraHotCache" and "CodeCache" segments
            if ("ExtraHotCache".equals(name)) {
                hotSegmentStart = start;
                hotSegmentEnd = end;
            } else {
                System.out.println("UNEXPECTED SEGMENT: >" + name + "<");
            }
        }
        boolean addMethod(long addr, String name) {
            if (addr >= hotSegmentStart && addr <= hotSegmentEnd) {
                hotSegmentMethods.add(name);
            } else if (addr >= c2SegmentStart && addr <= c2SegmentEnd) {
                c1SegmentMethods.add(name);
            } else if (addr >= c1SegmentStart && addr <= c1SegmentEnd) {
                c2SegmentMethods.add(name);
            } else if (addr >= nonmethodSegmentStart && addr <= nonmethodSegmentEnd) {
                nonnmethodSegmentMethods.add(name);
            } else {
                return false;
            }
            return true;
        }
        String getSegmentName(long addr) {
            if (addr >= hotSegmentStart && addr <= hotSegmentEnd) return SEGMENT_HOT;
            if (addr >= c2SegmentStart && addr <= c2SegmentEnd) return SEGMENT_NONPROF;
            if (addr >= c1SegmentStart && addr <= c1SegmentEnd) return SEGMENT_PROFILED;
            if (addr >= nonmethodSegmentStart && addr <= nonmethodSegmentEnd) return SEGMENT_NONNMETHOD;
            return "UNKNOWN";
        }
    }

    static String prepareCmdFile(String body) {
        File tmpDir = new File("tmp.test");
        tmpDir.mkdirs();
        File file = new File(tmpDir, "compiler.cmd");
        Writer out = null;
        try {
            out = new FileWriter(file);
            out.write(body);
        } catch (Exception ex) {
            ex.printStackTrace();
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Exception ex) {}
        }
        return file.getAbsolutePath();
    }

    CodeHeap runVM(CodeCacheConfiguration config) {
        return runVM(config.toJVMOptions());
    }

    CodeHeap runVM(ArrayList<String> commands) {

        CodeHeap codeHeap = new CodeHeap();
        commands.add("TestHotCodeHeap");
        ProcessBuilder pb = ProcessTools.createJavaProcessBuilder(commands);
        try {
            OutputAnalyzer output = new OutputAnalyzer(pb.start());
            Iterator<String> lines = output.asLines().iterator();
            while (lines.hasNext()) {
                String line = lines.next();

                // parsing subsequent lines:
                //   CodeHeap 'non-profiled nmethods': size=120028Kb used=373Kb max_used=373Kb free=119654Kb
                //   bounds [0x00007f764cac9000, 0x00007f764cd39000, 0x00007f7654000000]
                if (line.matches("^CodeHeap '.*") || line.contains("Cache: size=")) {
                    String[] parts = line.split("'");
                    String segmentName = (parts.length > 1) ? parts[1] : line.substring(0, line.indexOf(": "));
                    String bounds = lines.next();
                    System.out.println("bounds: " + bounds);
                    bounds = bounds.substring(bounds.indexOf('[') + 1);
                    bounds = bounds.substring(0, bounds.indexOf(']'));
                    String[] addr = bounds.split(", ");
                    String segmentStart = addr[0];
                    String segmentEnd = addr[2];
                    long start = Long.parseLong(segmentStart.replace("0x", ""), 16);
                    long end   = Long.parseLong(segmentEnd.replace("0x", ""), 16);
                    codeHeap.addSegment(segmentName, start, end);
                }

                String nmethodPattern = ".*[0-9]+ +[0-9]+ +[0-9]+.*[0x.*].*";
                // parsing nmethod info:
                //   11 1 0 java.lang.Enum.ordinal()I [0x00007f91e0ac9610, 0x00007f91e0ac97a0 - 0x00007f91e0ac9870]
                //   12 0 0 java.lang.Object.hashCode()I [0x00007f91e0ac9910, 0x00007f91e0ac9aa0 - 0x00007f91e0ac9c88]
                if (line.matches(nmethodPattern)) {
                    String addrStr = line.substring(line.indexOf("[0x") + 3);
                    addrStr = addrStr.substring(0, addrStr.indexOf(","));
                    long addr = Long.parseLong(addrStr, 16);
                    String name = line.split(" ")[3];
                    name = name.substring(0, name.indexOf("("));
                    String fields[] = line.split("\\s+");
                    int compilationLevel = Integer.valueOf(fields[1]);
                    if (compilationLevel == 4) { // c2
                        boolean isHot = (addr >= codeHeap.hotSegmentStart) && (addr < codeHeap.hotSegmentEnd);
                        boolean ok = codeHeap.addMethod(addr, name);
                        if (!ok) {
                            System.out.println(output);
                            throw new AssertionError("nmethod does not belong to any CodeHeap segment: " + name);
                        }
                    }
                }
            }
        } catch (Exception ex) { ex.printStackTrace(); }

        return codeHeap;
    }

    // main() runs in a separate VM: here we execute jcmd commands and exit
    public static void main(String args[]) {
        JMXExecutor exec = new JMXExecutor();
        exec.execute("Compiler.codecache");
        exec.execute("Compiler.codelist");
    }

    void checkJavaMethodsBelongsToHotSegmentOnly(CodeHeap codeHeap) {
        if (codeHeap.hotSegmentMethods.isEmpty()) {
            reportError("hot segment is empty!");
        }
        codeHeap.hotSegmentMethods.forEach((name) -> {
            if (!name.startsWith("java")) { reportError("hot segment contains wrong method: " + name); }
        });
        codeHeap.c1SegmentMethods.forEach((name) -> {
            if (name.startsWith("java")) { reportError("c1 segment contains wrong name: " + name); }
        });
        codeHeap.c2SegmentMethods.forEach((name) -> {
            if (name.startsWith("java")) { reportError("c2 segment contains wrong name: " + name); }
        });
    }

    class CodeCacheConfiguration {
        ArrayList<String> options = new ArrayList<String>();
        CodeCacheConfiguration() {
            options.add("-Xbootclasspath/a:.");
            options.add("-XX:+UnlockDiagnosticVMOptions");
            options.add("-Xcomp");
            options.add("-Xbatch");
            options.add("-XX:+ExtraHotCodeCache");
        }
        ArrayList<String> toJVMOptions() {
            return options;
        }
        CodeCacheConfiguration setCompileCommand(String commands) {
            String cmdFile = prepareCmdFile(commands);
            options.add("-XX:CompileCommandFile=" + cmdFile);
            return this;
        }
        CodeCacheConfiguration setCompilerDirectives(String commands) {
            String cmdFile = prepareCmdFile(commands);
            options.add("-XX:CompilerDirectivesFile=" + cmdFile);
            return this;
        }
        CodeCacheConfiguration setSegmented(boolean segmentedOn) {
            options.add("-XX:" + (segmentedOn ? "+" : "-") + "SegmentedCodeCache");
            return this;
        }
        CodeCacheConfiguration setTiered(boolean tieredOn) {
            options.add("-XX:" + (tieredOn ? "+" : "-") + "TieredCompilation");
            return this;
        }
        CodeCacheConfiguration addOption(String option) {
            options.add(option);
            return this;
        }
    }

    @Test
    public void testCommandFile() {
        CodeCacheConfiguration config = new CodeCacheConfiguration().setSegmented(true).setCompileCommand("option java*::* ExtraHot");
        CodeHeap codeHeap = runVM(config);
        checkJavaMethodsBelongsToHotSegmentOnly(codeHeap);
    }

    @Test
    public void testDirectivesFile() {
        CodeCacheConfiguration config = new CodeCacheConfiguration().setSegmented(true).
            setCompilerDirectives("[ { match: [ \"java*::*\" ], c2: { ExtraHot: true } } ]");
        CodeHeap codeHeap = runVM(config);
        checkJavaMethodsBelongsToHotSegmentOnly(codeHeap);
    }

    @Test
    public void testSegmentedNonTiered() {
        CodeCacheConfiguration config = new CodeCacheConfiguration().setSegmented(true).setTiered(false).
            setCompilerDirectives("[ { match: [ \"java*::*\" ], c2: { ExtraHot: true } } ]");
        CodeHeap codeHeap = runVM(config);
        checkJavaMethodsBelongsToHotSegmentOnly(codeHeap);
    }

    @Test
    public void testNonsegmented() {
        CodeCacheConfiguration config = new CodeCacheConfiguration().setSegmented(false).
            setCompilerDirectives("[ { match: [ \"java*::*\" ], c2: { ExtraHot: true } } ]");
        CodeHeap codeHeap = runVM(config);
        checkJavaMethodsBelongsToHotSegmentOnly(codeHeap);
    }

    @Test
    public void testEmptyHotSegment() {
        CodeCacheConfiguration config = new CodeCacheConfiguration().addOption("-XX:ExtraHotCodeHeapSize=10K");
        CodeHeap codeHeap = runVM(config);
        if (!codeHeap.hotSegmentMethods.isEmpty()) {
            reportError("hot segment must be empty!");
        }
    }

    @Test
    public void testSmallHotSegment() {
        CodeCacheConfiguration config = new CodeCacheConfiguration().setSegmented(true).
            setCompileCommand("option java*::* ExtraHot").
            addOption("-XX:ExtraHotCodeHeapSize=100K");
        CodeHeap codeHeap = runVM(config);

        if (codeHeap.hotSegmentMethods.isEmpty()) {
            reportError("hot segment should not be empty");
        }
        // Hot segment is full. Fallback to NonProfiled heap.
        for (String name : codeHeap.c2SegmentMethods) {
            if (name.startsWith("java")) {
                return; // OK
            }
        }
        reportError("remaining methods must go to non-profiled segment");
    }

    void reportError(String msg) {
        System.out.println(msg);
        throw new AssertionError(msg);
    }
}
