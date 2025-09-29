import java.io.*;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

public class ProjectSnapshotCollector {

    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", ".idea", "node_modules", "target", "build",
            "__pycache__", ".gradle", ".vscode"
    );

    private final Path root;
    private final Path selfPath;

    public ProjectSnapshotCollector(Path root) throws URISyntaxException {
        this.root = root;
        this.selfPath = Paths.get(ProjectSnapshotCollector.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
    }

    // ---------------- MODES ----------------

    /** Mode 1: All files with full content (small files only). */
    public ProjectSnapshot collectAll() throws IOException {
        List<FileInfo> files = collectMetadata();
        List<FileInfo> withContent = files.stream()
                .map(f -> f.withContent(readSmallFile(root.resolve(f.relativePath()))))
                .collect(Collectors.toList());
        return new ProjectSnapshot(root.toString(), withContent);
    }

    /** Mode 2: Git diff → metadata for all files, full content only for changed files. */
    public ProjectSnapshot collectGitDiff() throws IOException {
        Map<String, String> gitChanges = collectGitChanges();
        List<FileInfo> files = collectMetadata();

        List<FileInfo> updated = new ArrayList<>();
        for (FileInfo f : files) {
            String status = gitChanges.getOrDefault(f.relativePath(), "clean");
            String content = null;
            if (!status.equals("clean") && !status.equals("Deleted")) {
                content = readSmallFile(root.resolve(f.relativePath()));
            }
            updated.add(f.withGitStatus(status).withContent(content));
        }
        return new ProjectSnapshot(root.toString(), updated);
    }

    /** Mode 3: Minimal → metadata only, no contents. */
    public ProjectSnapshot collectMinimal() throws IOException {
        return new ProjectSnapshot(root.toString(), collectMetadata());
    }

    // ---------------- INTERNAL HELPERS ----------------

    private List<FileInfo> collectMetadata() throws IOException {
        Map<String, String> gitChanges = collectGitChanges();

        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(p -> IGNORED_DIRS.stream()
                            .noneMatch(part -> p.toString().contains(File.separator + part + File.separator)))
                    .filter(this::notSelf)
                    .map(this::toFileInfo)
                    .map(f -> f.withGitStatus(gitChanges.getOrDefault(f.relativePath(), "clean")))
                    .sorted(Comparator.comparing(FileInfo::relativePath))
                    .collect(Collectors.toList());
        }
    }

    private boolean notSelf(Path p) {
        try {
            return !p.toRealPath().startsWith(selfPath.toRealPath());
        } catch (IOException e) {
            return true;
        }
    }

    private Map<String, String> collectGitChanges() {
        Map<String, String> changes = new HashMap<>();
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "status", "--porcelain");
            pb.directory(root.toFile());
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.length() > 3) {
                        String code = line.substring(0, 2).trim();
                        String file = line.substring(3).trim().replace("\\", "/");
                        changes.put(file, decodeGitStatus(code));
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("⚠️ Git not available or not a repository.");
        }
        return changes;
    }

    private String decodeGitStatus(String code) {
        return switch (code) {
            case "M" -> "Modified";
            case "A" -> "Added";
            case "D" -> "Deleted";
            case "R" -> "Renamed";
            case "??" -> "Untracked";
            default -> "Changed";
        };
    }

    private FileInfo toFileInfo(Path path) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            String relPath = root.relativize(path).toString().replace("\\", "/");
            String size = humanReadableByteCount(attrs.size());
            String modified = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis()));
            String lang = guessLanguage(path);
            return new FileInfo(relPath, size, modified, lang, null, "clean");
        } catch (IOException e) {
            return new FileInfo(path.toString(), "?", "?", "unknown", null, "clean");
        }
    }

    private String readSmallFile(Path path) {
        try {
            if (Files.exists(path) && Files.size(path) < 200 * 1024) { // 200KB max
                return Files.readString(path);
            }
        } catch (IOException ignored) {}
        return null;
    }

    private String guessLanguage(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".java")) return "Java";
        if (name.endsWith(".py")) return "Python";
        if (name.endsWith(".ts")) return "TypeScript";
        if (name.endsWith(".js")) return "JavaScript";
        if (name.endsWith(".md")) return "Markdown";
        if (name.endsWith(".json")) return "JSON";
        if (name.endsWith(".yml") || name.endsWith(".yaml")) return "YAML";
        if (name.endsWith(".xml")) return "XML";
        if (name.endsWith(".html") || name.endsWith(".htm")) return "HTML";
        return "Other";
    }

    private static String humanReadableByteCount(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return new DecimalFormat("#.##").format(bytes / Math.pow(1024, exp)) + " " + pre + "B";
    }

    // ---------------- SNAPSHOT CONTAINER ----------------

    public static class ProjectSnapshot {
        private final String rootPath;
        private final List<FileInfo> files;

        public ProjectSnapshot(String rootPath, List<FileInfo> files) {
            this.rootPath = rootPath;
            this.files = files;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("📂 Project Snapshot at: " + rootPath + "\n");
            for (FileInfo file : files) {
                sb.append(" - ").append(file.relativePath)
                        .append(" | ").append(file.language)
                        .append(" | ").append(file.size)
                        .append(" | modified ").append(file.modified)
                        .append(" | git: ").append(file.gitStatus());
                if (file.content != null) {
                    sb.append("\n   ⮑ Content (truncated): ")
                      .append(file.content.substring(0, Math.min(200, file.content.length())))
                      .append(file.content.length() > 200 ? "..." : "");
                }
                sb.append("\n");
            }
            return sb.toString();
        }

        /** Save snapshot as JSON */
        public void saveAsJson(Path output) throws IOException {
            ObjectMapper mapper = new ObjectMapper();
            ObjectWriter writer = mapper.writerWithDefaultPrettyPrinter();
            writer.writeValue(output.toFile(), this);
        }
    }

    public static record FileInfo(String relativePath, String size,
                                  String modified, String language,
                                  String content, String gitStatus) {
        public FileInfo withContent(String newContent) {
            return new FileInfo(relativePath, size, modified, language, newContent, gitStatus);
        }
        public FileInfo withGitStatus(String newStatus) {
            return new FileInfo(relativePath, size, modified, language, content, newStatus);
        }
    }

    // ---------------- MAIN ----------------

    public static void main(String[] args) throws Exception {
        Path currentDir = args.length > 0 ? Path.of(args[0]) : Path.of("").toAbsolutePath();
        ProjectSnapshotCollector collector = new ProjectSnapshotCollector(currentDir);

        System.out.println("\n📂 Project Snapshot Menu");
        System.out.println("1. All (full project contents: folders, files, code)");
        System.out.println("2. Git diff (only changes, with full contents)");
        System.out.println("3. Minimal (structure + metadata only)");
        System.out.print("Choose mode [1/2/3]: ");

        Scanner scanner = new Scanner(System.in);
        String choice = scanner.nextLine().trim();

        ProjectSnapshot snapshot;
        switch (choice) {
            case "1" -> snapshot = collector.collectAll();
            case "2" -> snapshot = collector.collectGitDiff();
            case "3" -> snapshot = collector.collectMinimal();
            default -> {
                System.out.println("Invalid choice, defaulting to minimal.");
                snapshot = collector.collectMinimal();
            }
        }

        // Print report
        System.out.println(snapshot);

        // Save JSON too
        Path outFile = currentDir.resolve("snapshot.json");
        snapshot.saveAsJson(outFile);
        System.out.println("✅ Snapshot saved to " + outFile);
    }
}
