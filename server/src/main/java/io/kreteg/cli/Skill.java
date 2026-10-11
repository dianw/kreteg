package io.kreteg.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The agent skill, {@code client/skill} in the repo, which the build copies into the binary, so {@code kreteg guide}
 * always matches the binary it comes with. The reference files are the ones {@code SKILL.md} links to; native images
 * can't list resource directories, and the links are there anyway.
 */
final class Skill {

    /** A file of the skill, with its path relative to the skill directory. */
    record SkillFile(String path, String content) {
    }

    static final String MAIN = "SKILL.md";

    private static final Pattern REFERENCE = Pattern.compile("references/[A-Za-z0-9._-]+\\.md");

    private Skill() {
    }

    /** {@code SKILL.md} first, then each reference file it links to, in the order of their first link. */
    static List<SkillFile> files() {
        String main = read(MAIN);
        Set<String> references = new LinkedHashSet<>();
        Matcher matcher = REFERENCE.matcher(main);
        while (matcher.find()) {
            references.add(matcher.group());
        }
        List<SkillFile> files = new ArrayList<>();
        files.add(new SkillFile(MAIN, main));
        references.forEach(path -> files.add(new SkillFile(path, read(path))));
        return files;
    }

    /** The whole skill as one read: {@code SKILL.md} without its frontmatter, then the references after it. */
    static String guide() {
        StringBuilder guide = new StringBuilder();
        for (SkillFile file : files()) {
            if (file.path().equals(MAIN)) {
                guide.append(withoutFrontmatter(file.content()).strip()).append('\n');
            } else {
                guide.append("\n---\n\n").append(file.content().strip()).append('\n');
            }
        }
        return guide.toString();
    }

    /**
     * Writes the skill into {@code dir} as a skills directory expects it, each file moved into place, and removes
     * reference files that are no longer part of it.
     */
    static List<Path> write(Path dir) throws IOException {
        List<SkillFile> files = files();
        Files.createDirectories(dir.resolve("references"));
        List<Path> written = new ArrayList<>();
        for (SkillFile file : files) {
            Path target = dir.resolve(file.path());
            Path temp = target.resolveSibling(target.getFileName() + ".new");
            Files.writeString(temp, file.content(), StandardCharsets.UTF_8);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            written.add(target);
        }
        try (DirectoryStream<Path> references = Files.newDirectoryStream(dir.resolve("references"), "*.md")) {
            for (Path reference : references) {
                if (!written.contains(reference)) {
                    Files.delete(reference);
                }
            }
        }
        return written;
    }

    static String withoutFrontmatter(String markdown) {
        if (!markdown.startsWith("---\n")) {
            return markdown;
        }
        int end = markdown.indexOf("\n---\n", 4);
        return end < 0 ? markdown : markdown.substring(end + "\n---\n".length());
    }

    private static String read(String path) {
        try (InputStream in = Skill.class.getResourceAsStream("skill/" + path)) {
            if (in == null) {
                throw new IllegalStateException("skill file " + path + " is missing from this build");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
