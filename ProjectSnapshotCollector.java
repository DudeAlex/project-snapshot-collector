import java.io.*;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.DecimalFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

public class ProjectSnapshotCollector {

    // ---------------- CONFIG ----------------

    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", ".idea", ".vscode", ".gradle", ".mvn", "snapshots",
            "target", "build", "out", "node_modules", "nbproject", "nbbuild", "dist", "__pycache__"
    );

    private static final Set<String> IGNORED_FILES = Set.of(
            "mvnw", "mvnw.cmd",
            "snapshot.json",
            "projectsnapshotcollector.java",
            "projectsnapshotcollector.class"
    );

    private static final String[] SECRET_NAME_PATTERNS = {
            ".env", "secrets", "secret", "credentials", "keystore", "key", "pem", "p12", "pfx"
    };
    private static final String[] BINARY_EXTS = {
            ".jar",".class",".png",".jpg",".jpeg",".gif",".bmp",".ico",".pdf",".zip",".tar",".gz",".rar",
            ".7z",".mp4",".mp3",".wav",".mov",".exe",".dll"
    };

    private static final Set<String> CODE_EXT_WHITELIST = Set.of(
            ".java",".kt",".kts",".scala",".groovy",
            ".py",".rb",".go",".rs",
            ".js",".jsx",".ts",".tsx",
            ".json",".yml",".yaml",".xml",".properties",".toml",".ini",".gradle",".md",".txt",".html",".htm",".css"
    );

    // JSON content size cap per file
    private static final long MAX_JSON_FILE_BYTES = 200 * 1024; // 200KB
    // TXT content size cap per file (safety)
    private static final int MAX_TXT_BYTES_PER_FILE  = 500 * 1024; // 500KB

    // ---------------- STATE ----------------

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

    /** Mode 1: All files with full content (for small code/text files). */
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
                    .filter(this::notSnapshotArtifacts)
                    .filter(p -> !isIgnored(p))
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

    private boolean notSnapshotArtifacts(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        if (name.startsWith("snapshot-") && (name.endsWith(".json") || name.endsWith(".txt"))) return false;
        return true;
    }

    private boolean isIgnored(Path path) {
        String name = path.getFileName().toString().toLowerCase();

        if (IGNORED_FILES.contains(name)) return true;

        for (String ext : BINARY_EXTS) if (name.endsWith(ext)) return true;
        for (String pat : SECRET_NAME_PATTERNS) if (name.contains(pat)) return true;

        String rp = root.relativize(path).toString().replace("\\", "/").toLowerCase();
        if (rp.endsWith("/projectsnapshotcollector.java")) return true;
        if (rp.endsWith("/projectsnapshotcollector.class")) return true;

        return false;
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
            if (!Files.exists(path)) return null;

            String name = path.getFileName().toString().toLowerCase();

            for (String ext : BINARY_EXTS) if (name.endsWith(ext)) return null;
            for (String pat : SECRET_NAME_PATTERNS) if (name.contains(pat)) return null;

            long size = Files.size(path);
            if (size >= MAX_JSON_FILE_BYTES) return null;

            String ext = name.contains(".") ? name.substring(name.lastIndexOf(".")) : "";
            if (!CODE_EXT_WHITELIST.contains(ext)) return null;

            return Files.readString(path);
        } catch (IOException ignored) {}
        return null;
    }

    private String guessLanguage(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".java")) return "Java";
        if (name.endsWith(".kt") || name.endsWith(".kts")) return "Kotlin";
        if (name.endsWith(".scala")) return "Scala";
        if (name.endsWith(".groovy")) return "Groovy";
        if (name.endsWith(".py")) return "Python";
        if (name.endsWith(".ts")) return "TypeScript";
        if (name.endsWith(".tsx")) return "TSX";
        if (name.endsWith(".js")) return "JavaScript";
        if (name.endsWith(".jsx")) return "JSX";
        if (name.endsWith(".md")) return "Markdown";
        if (name.endsWith(".json")) return "JSON";
        if (name.endsWith(".yml") || name.endsWith(".yaml")) return "YAML";
        if (name.endsWith(".xml")) return "XML";
        if (name.endsWith(".html") || name.endsWith(".htm")) return "HTML";
        if (name.endsWith(".css")) return "CSS";
        if (name.endsWith(".properties")) return "Properties";
        if (name.endsWith(".toml")) return "TOML";
        if (name.endsWith(".ini")) return "INI";
        return "Other";
    }

    private static String humanReadableByteCount(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return new DecimalFormat("#.##").format(bytes / Math.pow(1024, exp)) + " " + pre + "B";
    }

    // ---------------- SNAPSHOT CONTAINERS ----------------

    public static record ProjectSnapshot(String rootPath, List<FileInfo> files) {
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("📂 Project Snapshot at: " + rootPath + "\n");
            for (FileInfo file : files) {
                sb.append(" - ").append(file.relativePath())
                        .append(" | ").append(file.language())
                        .append(" | ").append(file.size())
                        .append(" | modified ").append(file.modified())
                        .append(" | git: ").append(file.gitStatus())
                        .append("\n");
            }
            return sb.toString();
        }

        public void saveAsJson(Path output) throws IOException {
            ObjectMapper mapper = new ObjectMapper();
            ObjectWriter writer = mapper.writerWithDefaultPrettyPrinter();
            writer.writeValue(output.toFile(), this);
        }

        /** Save snapshot as TXT; if printContents==true, include full bodies (capped). */
        public void saveAsText(Path output, boolean printContents) throws IOException {
            try (BufferedWriter writer = Files.newBufferedWriter(output)) {
                writer.write("📂 Project Snapshot at: " + rootPath + "\n\n");
                for (FileInfo file : files) {
                    writer.write("══════════════════════════════════════════════════════════════\n");
                    writer.write("📄 " + file.relativePath() + " (" + file.language() + ", " + file.size()
                            + ", modified " + file.modified() + ", git: " + file.gitStatus() + ")\n");
                    if (printContents && file.content() != null) {
                        writer.write("──────────────── FILE CONTENT ────────────────\n");
                        String content = file.content();
                        byte[] utf8 = content.getBytes();
                        if (utf8.length > MAX_TXT_BYTES_PER_FILE) {
                            int safeLen = Math.min(content.length(), MAX_TXT_BYTES_PER_FILE);
                            writer.write(content.substring(0, safeLen));
                            writer.write("\n…(truncated in TXT)\n");
                        } else {
                            writer.write(content);
                            writer.write("\n");
                        }
                    }
                    writer.write("\n");
                }
            }
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
        boolean printTxtBodies;
        switch (choice) {
            case "1" -> {
                snapshot = collector.collectAll();
                printTxtBodies = true;   // <— include FULL CONTENTS in TXT for Mode 1
            }
            case "2" -> {
                snapshot = collector.collectGitDiff();
                printTxtBodies = false;  // index-only
            }
            case "3" -> {
                snapshot = collector.collectMinimal();
                printTxtBodies = false;  // index-only
            }
            default -> {
                System.out.println("Invalid choice, defaulting to minimal.");
                snapshot = collector.collectMinimal();
                printTxtBodies = false;
            }
        }

        // Print concise index to console
        System.out.println(snapshot);

        // Save JSON + TXT snapshot with timestamp down to seconds
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        Path snapshotsDir = currentDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);

        Path jsonOut = snapshotsDir.resolve("snapshot-" + timestamp + ".json");
        Path txtOut  = snapshotsDir.resolve("snapshot-" + timestamp + ".txt");

        snapshot.saveAsJson(jsonOut);
        snapshot.saveAsText(txtOut, printTxtBodies);

        System.out.println("✅ Snapshot saved to " + jsonOut);
        System.out.println("✅ Snapshot saved to " + txtOut);
    }
}
