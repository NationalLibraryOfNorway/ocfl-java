package edu.wisc.library.ocfl.core;

import edu.wisc.library.ocfl.api.OcflObjectUpdater;
import edu.wisc.library.ocfl.api.OcflOption;
import edu.wisc.library.ocfl.api.util.Enforce;
import edu.wisc.library.ocfl.core.model.VersionId;
import edu.wisc.library.ocfl.core.util.FileUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

/**
 * Default implementation of OcflObjectUpdater that is used by DefaultOcflRepository to provide write access to an object.
 */
public class DefaultOcflObjectUpdater implements OcflObjectUpdater {

    private InventoryUpdater inventoryUpdater;
    private Path stagingDir;

    private Set<String> newFiles;

    public DefaultOcflObjectUpdater(InventoryUpdater inventoryUpdater, Path stagingDir) {
        this.inventoryUpdater = Enforce.notNull(inventoryUpdater, "inventoryUpdater cannot be null");
        this.stagingDir = Enforce.notNull(stagingDir, "stagingDir cannot be null");
        newFiles = new HashSet<>();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OcflObjectUpdater addPath(Path sourcePath, String destinationPath, OcflOption... ocflOptions) {
        Enforce.notNull(sourcePath, "sourcePath cannot be null");
        Enforce.notBlank(destinationPath, "destinationPath cannot be blank");

        var normalized = normalizeDestinationPath(destinationPath);
        var stagingDst = stagingDir.resolve(normalized);
        var files = FileUtil.findFiles(sourcePath);

        if (files.size() == 0) {
            throw new IllegalArgumentException(String.format("No files were found under %s to add", sourcePath));
        }

        files.forEach(file -> {
            var sourceRelative = sourcePath.relativize(file);
            var stagingFullPath = stagingDst.resolve(sourceRelative);
            var stagingRelative = stagingDir.relativize(stagingFullPath);
            var isNew = inventoryUpdater.addFile(file, stagingRelative, ocflOptions);
            if (isNew) {
                FileUtil.copyFileMakeParents(file, stagingFullPath);
                newFiles.add(stagingRelative.toString());
            }
        });

        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OcflObjectUpdater writeFile(InputStream input, String destinationPath, OcflOption... ocflOptions) {
        Enforce.notNull(input, "input cannot be null");
        Enforce.notBlank(destinationPath, "destinationPath cannot be blank");

        var normalized = normalizeDestinationPath(destinationPath);
        var stagingDst = stagingDir.resolve(normalized);
        var stagingRelative = stagingDir.relativize(stagingDst);

        FileUtil.createDirectories(stagingDst.getParent());
        copyInputStream(input, stagingDst);

        var isNew = inventoryUpdater.addFile(stagingDst, stagingRelative, ocflOptions);
        if (!isNew) {
            delete(stagingDst);
        } else {
            newFiles.add(stagingRelative.toString());
        }

        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OcflObjectUpdater removeFile(String path) {
        Enforce.notBlank(path, "path cannot be blank");

        inventoryUpdater.removeFile(path);

        if (newFiles.remove(path)) {
            inventoryUpdater.removeFileFromManifest(path);
            delete(stagingDir.resolve(path));
            cleanupEmptyDirs(stagingDir);
        }

        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OcflObjectUpdater renameFile(String sourcePath, String destinationPath, OcflOption... ocflOptions) {
        Enforce.notBlank(sourcePath, "sourcePath cannot be blank");
        Enforce.notBlank(destinationPath, "destinationPath cannot be blank");

        var normalizedDestination = normalizeDestinationPath(destinationPath).toString();

        if (!newFiles.remove(sourcePath)) {
            inventoryUpdater.renameFile(sourcePath, normalizedDestination, ocflOptions);
        } else {
            // TODO Things get complicated when new-to-version files are mutated. Perhaps this should just not be allowed.
            newFiles.add(normalizedDestination);
            var destination = stagingDir.resolve(normalizedDestination);
            moveFile(stagingDir.resolve(sourcePath), destination);
            cleanupEmptyDirs(stagingDir);
            inventoryUpdater.removeFile(sourcePath);
            inventoryUpdater.removeFileFromManifest(sourcePath);
            inventoryUpdater.addFile(destination, stagingDir.relativize(destination), ocflOptions);
        }

        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OcflObjectUpdater reinstateFile(String sourceVersionId, String sourcePath, String destinationPath, OcflOption... ocflOptions) {
        Enforce.notBlank(sourceVersionId, "sourceVersionId cannot be blank");
        Enforce.notBlank(sourcePath, "sourcePath cannot be blank");
        Enforce.notBlank(destinationPath, "destinationPath cannot be blank");

        var normalizedDestination = normalizeDestinationPath(destinationPath).toString();

        inventoryUpdater.reinstateFile(VersionId.fromValue(sourceVersionId), sourcePath, normalizedDestination, ocflOptions);

        return this;
    }

    /*
     * This is necessary to ensure that the specified paths are contained within the object root
     */
    private Path normalizeDestinationPath(String destinationPath) {
        var normalized = Paths.get(destinationPath).normalize();
        validateDestinationPath(normalized);
        return normalized;
    }

    private void validateDestinationPath(Path destination) {
        if (destination.isAbsolute()) {
            throw new IllegalArgumentException(
                    String.format("Invalid destination %s. Path must be relative the object root.", destination));
        }

        if (destination.startsWith("..")) {
            throw new IllegalArgumentException(
                    String.format("Invalid destination %s. Path cannot be outside of object root.", destination));
        }
    }

    private void copyInputStream(InputStream input, Path dst) {
        try {
            Files.copy(input, dst);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void delete(Path path) {
        try {
            Files.delete(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void moveFile(Path source, Path destination) {
        try {
            Files.createDirectories(destination.getParent());
            Files.move(source, destination);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void cleanupEmptyDirs(Path root) {
        try (var files = Files.walk(root)) {
            files.filter(Files::isDirectory)
                    .filter(f -> !f.equals(root))
                    .filter(f -> f.toFile().list().length == 0)
                    .forEach(this::delete);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
