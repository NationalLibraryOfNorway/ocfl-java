package edu.wisc.library.ocfl.core.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import edu.wisc.library.ocfl.api.OcflFileRetriever;
import edu.wisc.library.ocfl.api.exception.FixityCheckException;
import edu.wisc.library.ocfl.api.exception.NotFoundException;
import edu.wisc.library.ocfl.api.exception.ObjectOutOfSyncException;
import edu.wisc.library.ocfl.api.exception.RuntimeIOException;
import edu.wisc.library.ocfl.api.util.Enforce;
import edu.wisc.library.ocfl.core.OcflConstants;
import edu.wisc.library.ocfl.core.concurrent.ExecutorTerminator;
import edu.wisc.library.ocfl.core.concurrent.ParallelProcess;
import edu.wisc.library.ocfl.core.mapping.ObjectIdPathMapper;
import edu.wisc.library.ocfl.core.model.DigestAlgorithm;
import edu.wisc.library.ocfl.core.model.Inventory;
import edu.wisc.library.ocfl.core.model.Version;
import edu.wisc.library.ocfl.core.model.VersionId;
import edu.wisc.library.ocfl.core.util.DigestUtil;
import edu.wisc.library.ocfl.core.util.FileUtil;
import edu.wisc.library.ocfl.core.util.InventoryMapper;
import edu.wisc.library.ocfl.core.util.NamasteTypeFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class FileSystemOcflStorage implements OcflStorage {

    private static final Logger LOG = LoggerFactory.getLogger(FileSystemOcflStorage.class);

    private Path repositoryRoot;
    private ObjectIdPathMapper objectIdPathMapper;
    private InventoryMapper inventoryMapper;

    private ParallelProcess parallelProcess;

    /**
     * Creates a new FileSystemOcflStorage object. Its thread pool is size is set to the number of available processors.
     *
     * @param repositoryRoot OCFL repository root directory
     * @param objectIdPathMapper Mapper for mapping object ids to paths within the repository root
     */
    public FileSystemOcflStorage(Path repositoryRoot, ObjectIdPathMapper objectIdPathMapper) {
        this(repositoryRoot, objectIdPathMapper, Runtime.getRuntime().availableProcessors());
    }

    /**
     * Creates a new FileSystemOcflStorage object.
     *
     * @param repositoryRoot OCFL repository root directory
     * @param objectIdPathMapper Mapper for mapping object ids to paths within the repository root
     * @param threadPoolSize The size of the object's thread pool, used when calculating digests
     */
    public FileSystemOcflStorage(Path repositoryRoot, ObjectIdPathMapper objectIdPathMapper, int threadPoolSize) {
        this.repositoryRoot = Enforce.notNull(repositoryRoot, "repositoryRoot cannot be null");
        this.objectIdPathMapper = Enforce.notNull(objectIdPathMapper, "objectIdPathMapper cannot be null");
        Enforce.expressionTrue(threadPoolSize > 0, threadPoolSize, "threadPoolSize must be greater than 0");

        this.inventoryMapper = InventoryMapper.defaultMapper(); // This class will never serialize an Inventory, so the pretty print doesn't matter
        this.parallelProcess = new ParallelProcess(ExecutorTerminator.addShutdownHook(Executors.newFixedThreadPool(threadPoolSize)));
    }

    public FileSystemOcflStorage setInventoryMapper(InventoryMapper inventoryMapper) {
        this.inventoryMapper = Enforce.notNull(inventoryMapper, "inventoryMapper cannot be null");
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Inventory loadInventory(String objectId) {
        var objectRootPath = objectRootPathFull(objectId);

        if (Files.exists(objectRootPath)) {
            return parseInventory(inventoryPath(objectRootPath));
        }

        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void storeNewVersion(Inventory inventory, Path stagingDir) {
        var objectRootPath = objectRootPathFull(inventory.getId());

        try {
            if (isFirstVersion(inventory)) {
                setupNewObjectDirs(objectRootPath);
            }

            var versionPath = objectRootPath.resolve(inventory.getHead().toString());

            try {
                Files.createDirectory(versionPath);
            } catch (FileAlreadyExistsException e) {
                throw new ObjectOutOfSyncException(
                        String.format("Failed to create a new version of object %s. Changes are out of sync with the current object state.", inventory.getId()));
            } catch (IOException e) {
                throw new RuntimeIOException(e);
            }

            FileUtil.moveDirectory(stagingDir, versionPath);
            versionContentFixityCheck(inventory, inventory.getHeadVersion(), versionPath.resolve(inventory.getContentDirectory()));
            copyInventory(versionPath, objectRootPath, inventory);
            // TODO verify inventory integrity again?
        } catch (RuntimeException e) {
            rollbackChanges(objectRootPath, inventory);
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, OcflFileRetriever> getObjectStreams(Inventory inventory, VersionId versionId) {
        var objectRootPath = objectRootPathFull(inventory.getId());
        var version = ensureVersion(inventory, versionId);
        var algorithm = inventory.getDigestAlgorithm();

        var map = new HashMap<String, OcflFileRetriever>(version.getState().size());

        version.getState().forEach((digest, paths) -> {
            var srcPath = objectRootPath.resolve(ensureManifestPath(inventory, digest));

            paths.forEach(path -> {
                map.put(path, new FileSystemOcflFileRetriever(srcPath, algorithm, digest));
            });
        });

        return map;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reconstructObjectVersion(Inventory inventory, VersionId versionId, Path stagingDir) {
        var objectRootPath = objectRootPathFull(inventory.getId());
        var version = ensureVersion(inventory, versionId);

        parallelProcess.collection(version.getState().entrySet(), entry -> {
            var id = entry.getKey();
            var files = entry.getValue();

            var src = ensureManifestPath(inventory, id);
            var srcPath = objectRootPath.resolve(src);

            for (var dstPath : files) {
                var path = stagingDir.resolve(dstPath);

                if (Thread.interrupted()) {
                    break;
                } else {
                    FileUtil.copyFileMakeParents(srcPath, path);
                }

                if (Thread.interrupted()) {
                    break;
                } else {
                    var digest = DigestUtil.computeDigest(inventory.getDigestAlgorithm(), path);
                    var paths = inventory.getFilePaths(digest);
                    if (paths == null || !paths.contains(src)) {
                        throw new FixityCheckException(String.format("File %s in object %s failed its %s fixity check. Was: %s",
                                path, inventory.getId(), inventory.getDigestAlgorithm().getValue(), digest));
                    }
                }
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public InputStream retrieveFile(Inventory inventory, String fileId) {
        var objectRootPath = objectRootPathFull(inventory.getId());

        var filePath = inventory.getFilePath(fileId);

        if (filePath == null) {
            throw new NotFoundException(String.format("File %s does not exist in object %s.", fileId, inventory.getId()));
        }

        try {
            return Files.newInputStream(objectRootPath.resolve(filePath));
        } catch (IOException e) {
            throw new RuntimeIOException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void purgeObject(String objectId) {
        var objectRootPath = objectRootPathFull(objectId);

        if (Files.exists(objectRootPath)) {
            try (var paths = Files.walk(objectRootPath)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(f -> {
                            try {
                                Files.delete(f);
                            } catch (IOException e) {
                                throw new RuntimeIOException(String.format("Failed to delete file %s while purging object %s." +
                                        " The purge failed and may need to be deleted manually.", f, objectId), e);
                            }
                        });
            } catch (IOException e) {
                throw new RuntimeIOException(String.format("Failed to purge object %s at %s. The object may need to be deleted manually.",
                        objectId, objectRootPath), e);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean containsObject(String objectId) {
        return Files.exists(objectRootPathFull(objectId));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String objectRootPath(String objectId) {
        return objectIdPathMapper.map(objectId).toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initializeStorage(String ocflVersion) {
        if (!Files.exists(repositoryRoot)) {
            FileUtil.createDirectories(repositoryRoot);
        } else {
            Enforce.expressionTrue(Files.isDirectory(repositoryRoot), repositoryRoot,
                    "repositoryRoot must be a directory");
        }

        if (repositoryRoot.toFile().list().length == 0) {
            // setup new repo
            // TODO perhaps this should be moved somewhere else so it can be used by other storage implementations
            new NamasteTypeFile(ocflVersion).writeFile(repositoryRoot);
            writeOcflSpec(ocflVersion);
            writeOcflLayout();
        } else {
            validateExistingRepo(ocflVersion);
        }

        if (!Files.exists(repositoryRoot.resolve(OcflConstants.DEPOSIT_DIRECTORY))) {
            FileUtil.createDirectories(repositoryRoot.resolve(OcflConstants.DEPOSIT_DIRECTORY));
        }
    }

    private Path objectRootPathFull(String objectId) {
        return repositoryRoot.resolve(objectIdPathMapper.map(objectId));
    }

    private Path inventoryPath(Path rootPath) {
        return rootPath.resolve(OcflConstants.INVENTORY_FILE);
    }

    private Path inventorySidecarPath(Path rootPath, DigestAlgorithm digestAlgorithm) {
        return rootPath.resolve(OcflConstants.INVENTORY_FILE + "." + digestAlgorithm.getValue());
    }

    private Inventory parseInventory(Path inventoryPath) {
        verifyInventory(inventoryPath);
        return inventoryMapper.readValue(inventoryPath);
    }

    private void verifyInventory(Path inventoryPath) {
        var sidecarPath = findInventorySidecar(inventoryPath.getParent());
        var expectedDigest = readInventoryDigest(sidecarPath);
        var algorithm = getDigestAlgorithmFromSidecar(sidecarPath);

        var actualDigest = DigestUtil.computeDigest(algorithm, inventoryPath);

        if (!expectedDigest.equalsIgnoreCase(actualDigest)) {
            throw new FixityCheckException(String.format("Invalid inventory file: %s. Expected %s digest: %s; Actual: %s",
                    inventoryPath, algorithm.getValue(), expectedDigest, actualDigest));
        }
    }

    private Path findInventorySidecar(Path objectRootPath) {
        try (var files = Files.list(objectRootPath)) {
            var sidecars = files
                    .filter(file -> file.getFileName().toString().startsWith(OcflConstants.INVENTORY_FILE + "."))
                    .collect(Collectors.toList());

            if (sidecars.size() != 1) {
                throw new IllegalStateException(String.format("Expected there to be one inventory sidecar file, but found %s.",
                        sidecars.size()));
            }

            return sidecars.get(0);
        } catch (IOException e) {
            throw new RuntimeIOException(e);
        }
    }

    private String readInventoryDigest(Path inventorySidecarPath) {
        try {
            var parts = Files.readString(inventorySidecarPath).split("\\s");
            if (parts.length == 0) {
                throw new IllegalStateException("Invalid inventory sidecar file: " + inventorySidecarPath);
            }
            return parts[0];
        } catch (IOException e) {
            throw new RuntimeIOException(e);
        }
    }

    private DigestAlgorithm getDigestAlgorithmFromSidecar(Path inventorySidecarPath) {
        return DigestAlgorithm.fromValue(
                inventorySidecarPath.getFileName().toString().substring(OcflConstants.INVENTORY_FILE.length() + 1));
    }

    private boolean isFirstVersion(Inventory inventory) {
        return inventory.getVersions().size() == 1;
    }

    private void setupNewObjectDirs(Path objectRootPath) {
        FileUtil.createDirectories(objectRootPath);
        new NamasteTypeFile(OcflConstants.OCFL_OBJECT_VERSION).writeFile(objectRootPath);
    }

    private void copyInventory(Path sourcePath, Path destinationPath, Inventory inventory) {
        var digestAlgorithm = inventory.getDigestAlgorithm();

        FileUtil.copy(inventoryPath(sourcePath), inventoryPath(destinationPath),
                StandardCopyOption.REPLACE_EXISTING);
        FileUtil.copy(inventorySidecarPath(sourcePath, digestAlgorithm), inventorySidecarPath(destinationPath, digestAlgorithm),
                StandardCopyOption.REPLACE_EXISTING);
    }

    private void versionContentFixityCheck(Inventory inventory, Version version, Path versionContentPath) {
        var files = FileUtil.findFiles(versionContentPath);

        parallelProcess.collection(files, file -> {
            var fileRelativePath = versionContentPath.relativize(file);
            var expectedDigest = version.getFileId(fileRelativePath.toString());
            if (expectedDigest == null) {
                throw new IllegalStateException(String.format("File not found in object %s version %s: %s",
                        inventory.getId(), inventory.getHead(), fileRelativePath));
            } else {
                var actualDigest = DigestUtil.computeDigest(inventory.getDigestAlgorithm(), file);
                if (!expectedDigest.equalsIgnoreCase(actualDigest)) {
                    throw new FixityCheckException(String.format("File %s in object %s failed its %s fixity check. Expected: %s; Actual: %s",
                            file, inventory.getId(), inventory.getDigestAlgorithm().getValue(), expectedDigest, actualDigest));
                }
            }
        });
    }

    private void rollbackChanges(Path objectRootPath, Inventory inventory) {
        try {
            FileUtil.safeDeletePath(objectRootPath.resolve(inventory.getHead().toString()));
            if (isFirstVersion(inventory)) {
                FileUtil.safeDeletePath(objectRootPath);
            } else {
                var previousVersionRoot = objectRootPath.resolve(inventory.getHead().previousVersionId().toString());
                copyInventory(previousVersionRoot, objectRootPath, inventory);
            }
        } catch (RuntimeException e) {
            LOG.error("Failed to rollback changes to object {} cleanly.", inventory.getId(), e);
        }
    }

    private Version ensureVersion(Inventory inventory, VersionId versionId) {
        var version = inventory.getVersion(versionId);

        if (version == null) {
            throw new IllegalStateException(String.format("Object %s does not contain version %s", inventory.getId(), versionId));
        }

        return version;
    }

    private String ensureManifestPath(Inventory inventory, String id) {
        if (!inventory.manifestContainsId(id)) {
            throw new IllegalStateException(String.format("Missing manifest entry for %s in object %s.",
                    id, inventory.getId()));
        }
        return inventory.getFilePath(id);
    }

    private void validateExistingRepo(String ocflVersion) {
        String existingOcflVersion = null;

        for (var file : repositoryRoot.toFile().listFiles()) {
            if (file.isFile() && file.getName().startsWith("0=")) {
                existingOcflVersion = file.getName().substring(2);
                break;
            }
        }

        if (existingOcflVersion == null) {
            throw new IllegalStateException("OCFL root is missing its root conformance declaration.");
        } else if (!existingOcflVersion.equals(ocflVersion)) {
            throw new IllegalStateException(String.format("OCFL version mismatch. Expected: %s; Found: %s",
                    ocflVersion, existingOcflVersion));
        }

        // TODO how to verify layout file

        var objectRoot = identifyRandomObjectRoot(repositoryRoot);

        if (objectRoot != null) {
            var inventory = parseInventory(inventoryPath(objectRoot));
            var expectedPath = objectIdPathMapper.map(inventory.getId());
            var actualPath = repositoryRoot.relativize(objectRoot);
            if (!expectedPath.equals(actualPath)) {
                throw new IllegalStateException(String.format(
                        "The OCFL client was configured to use the following layout: %s." +
                                " This layout does not match the layout of existing objects in the repository." +
                        " Found object %s stored at %s, but was expecting it to be stored at %s.",
                        objectIdPathMapper.describeLayout(), inventory.getId(), actualPath, expectedPath
                ));
            }
        }
    }

    private void writeOcflSpec(String ocflVersion) {
        var ocflSpecFile = ocflVersion + ".txt";
        try (var ocflSpecStream = FileSystemOcflStorage.class.getClassLoader().getResourceAsStream(ocflSpecFile)) {
            Files.copy(ocflSpecStream, repositoryRoot.resolve(ocflSpecFile));
        } catch (IOException e) {
            throw new RuntimeIOException(e);
        }
    }

    private Path identifyRandomObjectRoot(Path root) {
        var objectRootHolder = new ArrayList<Path>(1);
        var objectMarkerPrefix = "0=ocfl_object";

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (dir.endsWith("deposit")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return super.preVisitDirectory(dir, attrs);
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (file.getFileName().toString().startsWith(objectMarkerPrefix)) {
                        objectRootHolder.add(file.getParent());
                        return FileVisitResult.TERMINATE;
                    }
                    return super.visitFile(file, attrs);
                }
            });
        } catch (IOException e) {
            throw new RuntimeIOException(e);
        }

        if (objectRootHolder.isEmpty()) {
            return null;
        }

        return objectRootHolder.get(0);
    }

    private void writeOcflLayout() {
        try {
            var map = new TreeMap<String, Object>(Comparator.naturalOrder());
            map.putAll(objectIdPathMapper.describeLayout());
            new ObjectMapper().configure(SerializationFeature.INDENT_OUTPUT, true)
                    .writeValue(repositoryRoot.resolve("ocfl_layout.json").toFile(), map);
        } catch (IOException e) {
            throw new RuntimeIOException(e);
        }
    }

}
