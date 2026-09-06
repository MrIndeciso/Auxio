package org.oxycblt.auxio.backup;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Raw, read-only snapshot. Deliberately has no Android or SQLite dependencies. */
public final class Snapshot {
    private static final Set<String> RUNTIME =
            new HashSet<>(Arrays.asList("cache", "code_cache", "lib"));

    public static final class Entry {
        public final Path source;
        public final String name;
        public final boolean directory;
        public final long size;
        public final long modified;
        public final String digest;

        Entry(Path source, String name, BasicFileAttributes a, String digest) {
            this.source = source;
            this.name = name;
            directory = a.isDirectory();
            size = directory ? 0 : a.size();
            modified = a.lastModifiedTime().toMillis();
            this.digest = digest;
        }

        String fingerprint() {
            return name + "\n" + directory + "\n" + size + "\n" + modified + "\n" + digest;
        }
    }

    public static final class Inventory {
        public final List<Entry> entries = new ArrayList<>();
        public final List<String> excluded = new ArrayList<>();
        public final List<String> absent = new ArrayList<>();
    }

    private static MessageDigest sha() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    public static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }

    private static String hash(Path file) throws IOException {
        MessageDigest digest = sha();
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[131072];
            int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    public static Inventory inventory(Map<String, Path> roots) throws IOException {
        Inventory out = new Inventory();
        for (Map.Entry<String, Path> root : roots.entrySet()) {
            String label = root.getKey();
            if (!label.matches("[a-z][a-z0-9-]*")) throw new IOException("Invalid root label");
            Path base = root.getValue().toAbsolutePath().normalize();
            BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(base, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
                out.absent.add(label);
                continue;
            }
            if (!attrs.isDirectory()) throw new IOException("Invalid storage root: " + label);
            Files.walkFileTree(base, new SimpleFileVisitor<Path>() {
                private boolean excluded(Path p) {
                    if (p.equals(base)) return false;
                    Path relative = base.relativize(p);
                    if (relative.getNameCount() == 1 && RUNTIME.contains(relative.toString())) {
                        out.excluded.add(label + "/" + relative);
                        return true;
                    }
                    return false;
                }

                private void add(Path p, BasicFileAttributes a) throws IOException {
                    String relative = base.relativize(p).toString().replace(File.separatorChar, '/');
                    String name = label + (relative.isEmpty() ? "" : "/" + relative);
                    if (!a.isDirectory() && !a.isRegularFile()) {
                        throw new IOException("Non-regular persistent entry: " + name);
                    }
                    String digest = a.isDirectory() ? null : hash(p);
                    BasicFileAttributes after = Files.readAttributes(p, BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS);
                    if (a.size() != after.size() ||
                            !a.lastModifiedTime().equals(after.lastModifiedTime()) ||
                            a.isDirectory() != after.isDirectory() ||
                            !Objects.equals(a.fileKey(), after.fileKey())) {
                        throw new IOException("Source changed during inventory: " + name);
                    }
                    out.entries.add(new Entry(p, name, a, digest));
                }

                @Override public FileVisitResult preVisitDirectory(Path p, BasicFileAttributes a)
                        throws IOException {
                    if (excluded(p)) return FileVisitResult.SKIP_SUBTREE;
                    add(p, a);
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFile(Path p, BasicFileAttributes a)
                        throws IOException {
                    if (!excluded(p)) add(p, a);
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFileFailed(Path p, IOException e)
                        throws IOException { throw e; }
            });
        }
        out.entries.sort(Comparator.comparing(e -> e.name));
        Collections.sort(out.excluded);
        Collections.sort(out.absent);
        return out;
    }

    public static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 32) out.append(String.format(Locale.ROOT, "\\u%04x", (int)c));
                    else out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static String strings(List<String> values) {
        StringJoiner out = new StringJoiner(",", "[", "]");
        for (String value : values) out.add(quote(value));
        return out.toString();
    }

    /** The completion manifest is emitted only after a second full source verification. */
    public static void write(Map<String, Path> roots, OutputStream output, String metadataJson)
            throws IOException {
        Inventory before = inventory(roots);
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(output))) {
            zip.setLevel(1);
            byte[] buffer = new byte[131072];
            for (Entry entry : before.entries) {
                ZipEntry ze = new ZipEntry("data/" + entry.name + (entry.directory ? "/" : ""));
                ze.setTime(entry.modified);
                zip.putNextEntry(ze);
                if (!entry.directory) {
                    MessageDigest digest = sha();
                    long size = 0;
                    try (InputStream in = Files.newInputStream(entry.source,
                            LinkOption.NOFOLLOW_LINKS)) {
                        int count;
                        while ((count = in.read(buffer)) != -1) {
                            zip.write(buffer, 0, count);
                            digest.update(buffer, 0, count);
                            size += count;
                        }
                    }
                    if (size != entry.size || !hex(digest.digest()).equals(entry.digest)) {
                        throw new IOException("Source changed during export: " + entry.name);
                    }
                }
                zip.closeEntry();
            }
            Inventory after = inventory(roots);
            if (before.entries.size() != after.entries.size() ||
                    !before.excluded.equals(after.excluded) || !before.absent.equals(after.absent)) {
                throw new IOException("Storage contents changed during export");
            }
            for (int i = 0; i < before.entries.size(); i++) {
                if (!before.entries.get(i).fingerprint().equals(after.entries.get(i).fingerprint())) {
                    throw new IOException("Source changed before completion: " + before.entries.get(i).name);
                }
            }
            StringJoiner entries = new StringJoiner(",", "[", "]");
            for (Entry e : before.entries) {
                entries.add("{\"path\":" + quote(e.name) + ",\"directory\":" + e.directory +
                        ",\"size\":" + e.size + ",\"modifiedMs\":" + e.modified +
                        ",\"sha256\":" + (e.digest == null ? "null" : quote(e.digest)) + "}");
            }
            StringJoiner rootJson = new StringJoiner(",", "{", "}");
            for (Map.Entry<String, Path> root : roots.entrySet()) {
                rootJson.add(quote(root.getKey()) + ":" + quote(root.getValue().toString()));
            }
            String manifest = "{\"format\":1,\"complete\":true,\"metadata\":" + metadataJson +
                    ",\"roots\":" + rootJson + ",\"absentRoots\":" + strings(before.absent) +
                    ",\"excludedRuntimePaths\":" + strings(before.excluded) +
                    ",\"entries\":" + entries + "}";
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
