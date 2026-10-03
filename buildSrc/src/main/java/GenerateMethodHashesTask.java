import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public abstract class GenerateMethodHashesTask extends DefaultTask {

    @InputDirectory
    public abstract DirectoryProperty getSourceDir();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @Input
    public abstract Property<String> getNamespace();

    @TaskAction
    public void generate() throws IOException, NoSuchAlgorithmException {
        File srcDir = getSourceDir().get().getAsFile();
        File outDir = getOutputDir().get().getAsFile();
        File outputFile = new File(outDir, getNamespace().get().replace(".", "/") + "/dexkit/cache/GeneratedMethodHashes.kt");

        Map<String, String> hashMap = new TreeMap<>();

        Files.walk(srcDir.toPath())
                .filter(p -> p.toFile().isFile() && p.toString().endsWith(".kt"))
                .forEach(file -> {
                    try {
                        String content = Files.readString(file);
                        if (!content.contains("IResolveDex")) return;

                        String cleanContent = content
                                .replaceAll("//[^\n]*", "")
                                .replaceAll("/\\*[\\s\\S]*?\\*/", "");

                        Matcher pkgMatcher = Pattern.compile("package\\s+([\\w.]+)").matcher(cleanContent);
                        String packageName = pkgMatcher.find() ? pkgMatcher.group(1) : null;

                        Pattern classPattern = Pattern.compile("\\b(?:class|object)\\s+(\\w+)\\b");
                        Matcher classMatcher = classPattern.matcher(cleanContent);
                        List<String[]> declarations = new ArrayList<>();
                        while (classMatcher.find()) {
                            declarations.add(new String[]{classMatcher.group(1), String.valueOf(classMatcher.start())});
                        }

                        // Register EVERY class in this file that implements IResolveDex.
                        // Files may contain multiple such feature objects (e.g. one Kotlin file
                        // declaring several related features); only hashing the first one left
                        // the rest without a HASHES entry, which crashed DexCacheManager at runtime.
                        for (int i = 0; i < declarations.size(); i++) {
                            String[] decl = declarations.get(i);
                            int matchStart = Integer.parseInt(decl[1]);
                            int braceIndex = cleanContent.indexOf('{', matchStart);
                            int closingBraceIndex = cleanContent.indexOf('}', matchStart);
                            int nextDeclIndex = i + 1 < declarations.size() ? Integer.parseInt(declarations.get(i + 1)[1]) : cleanContent.length();

                            if (braceIndex == -1 || braceIndex >= nextDeclIndex ||
                                    (closingBraceIndex != -1 && braceIndex >= closingBraceIndex)) {
                                continue;
                            }

                            String signature = cleanContent.substring(matchStart, braceIndex);
                            if (!signature.contains(":") || !Pattern.compile("\\bIResolveDex\\b").matcher(signature).find()) {
                                continue;
                            }

                            String className = decl[0];
                            String fullClassName = packageName != null ? packageName + "." + className : className;
                            String combinedBody = extractDexBlocks(cleanContent, braceIndex);
                            hashMap.put(fullClassName, md5Hex(combinedBody));
                        }
                    } catch (IOException | NoSuchAlgorithmException e) {
                        throw new RuntimeException(e);
                    }
                });

        outputFile.getParentFile().mkdirs();

        String hashEntries = hashMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> "\"" + e.getKey() + "\" to \"" + e.getValue() + "\"")
                .collect(Collectors.joining(", \n"));

        String content = "package " + getNamespace().get() + ".dexkit.cache\n\n" +
                "object GeneratedMethodHashes {\n" +
                "    val HASHES = mapOf(" + hashEntries + ")\n" +
                "}\n";

        Files.writeString(outputFile.toPath(), content);
    }

    /**
     * Extracts the hash-relevant body of a single class: its {@code resolveDex()} body
     * plus every inline {@code by dexClass/dexMethod/dexConstructor} block, all scoped
     * to the class body starting at {@code classBodyStart} (the class's opening brace).
     * Scoped extraction keeps multi-class files from attributing one class's blocks to another.
     */
    private String extractDexBlocks(String content, int classBodyStart)
            throws NoSuchAlgorithmException {
        int bodyEnd = findBalancedBraceEnd(content, classBodyStart);
        String classBody = content.substring(classBodyStart, bodyEnd);

        List<String> blocks = new ArrayList<>();

        Matcher resolveDexMatch = Pattern.compile("override\\s+fun\\s+resolveDex\\s*\\(").matcher(classBody);
        if (resolveDexMatch.find()) {
            int start = classBody.indexOf('{', resolveDexMatch.end());
            if (start != -1) {
                int end = findBalancedBraceEnd(classBody, start);
                blocks.add(classBody.substring(start, end + 1));
            }
        }

        Pattern inlinePattern = Pattern.compile("\\bby\\s+dex(?:Class|Method|Constructor)\\b");
        Pattern separatorPattern = Pattern.compile("\\b(val|fun|private|public|internal|class|object|override)\\b");
        Matcher inlineMatcher = inlinePattern.matcher(classBody);
        while (inlineMatcher.find()) {
            int startScan = inlineMatcher.end();
            int nextOpenBrace = classBody.indexOf('{', startScan);
            if (nextOpenBrace != -1) {
                String intermediate = classBody.substring(startScan, nextOpenBrace);
                if (!separatorPattern.matcher(intermediate).find()) {
                    int end = findBalancedBraceEnd(classBody, nextOpenBrace);
                    blocks.add(classBody.substring(nextOpenBrace, end + 1));
                }
            }
        }

        // A class may legitimately implement IResolveDex without any dex blocks (e.g. a
        // pure DB-query feature). Register the deterministic md5("") for it so it always
        // has a HASHES entry; if it later gains delegates, its hash changes and the cache
        // invalidates as expected.
        return String.join("\n", blocks);
    }

    /** Returns the index of the {@code '}'} matching the {@code '{'} at {@code openIndex}. */
    private int findBalancedBraceEnd(String content, int openIndex) {
        int count = 0;
        for (int j = openIndex; j < content.length(); j++) {
            char c = content.charAt(j);
            if (c == '{') count++;
            else if (c == '}') {
                count--;
                if (count == 0) return j;
            }
        }
        return content.length() - 1;
    }

    private String md5Hex(String body) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(body.getBytes());
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}