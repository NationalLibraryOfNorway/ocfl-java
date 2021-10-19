/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 University of Wisconsin Board of Regents
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package edu.wisc.library.ocfl.core.storage.cloud;

import edu.wisc.library.ocfl.api.OcflFileRetriever;
import edu.wisc.library.ocfl.api.exception.OcflFileAlreadyExistsException;
import edu.wisc.library.ocfl.api.exception.OcflIOException;
import edu.wisc.library.ocfl.api.exception.OcflNoSuchFileException;
import edu.wisc.library.ocfl.api.model.DigestAlgorithm;
import edu.wisc.library.ocfl.api.util.Enforce;
import edu.wisc.library.ocfl.core.storage.FileSystem;
import edu.wisc.library.ocfl.core.storage.Listing;
import edu.wisc.library.ocfl.core.storage.OcflObjectRootDirIterator;
import edu.wisc.library.ocfl.core.util.FileUtil;
import edu.wisc.library.ocfl.core.util.UncheckedFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

// TODO
public class CloudFileSystem implements FileSystem {

    private static final Logger LOG = LoggerFactory.getLogger(CloudFileSystem.class);

    private final CloudClient client;
    private final CloudOcflFileRetriever.Builder fileRetrieverBuilder;

    public CloudFileSystem(CloudClient client) {
        this.client = Enforce.notNull(client, "client cannot be null");
        this.fileRetrieverBuilder = CloudOcflFileRetriever.builder().cloudClient(client);
    }

    @Override
    public List<Listing> listDirectory(String directoryPath) {
        var listings = new ArrayList<Listing>();
        var result = client.listDirectory(directoryPath);

        result.getObjects().forEach(object -> {
            listings.add(Listing.file(object.getKeySuffix()));
        });
        result.getDirectories().forEach(dir -> {
            listings.add(Listing.directory(dir.getName()));
        });

        if (listings.isEmpty()) {
            throw new OcflNoSuchFileException(String.format("Directory %s does not exist", directoryPath));
        }

        return listings;
    }

    @Override
    public List<Listing> listRecursive(String directoryPath) {
        var listings = new ArrayList<Listing>();

        var result = client.list(withTrailingSlash(directoryPath));

        result.getObjects().forEach(object -> {
            listings.add(Listing.file(object.getKeySuffix()));
        });
        result.getDirectories().forEach(dir -> {
            listings.add(Listing.directory(dir.getName()));
        });

        if (listings.isEmpty()) {
            throw new OcflNoSuchFileException(String.format("Directory %s does not exist", directoryPath));
        }

        return listings;
    }

    @Override
    public OcflObjectRootDirIterator iterateObjects() {
        return new CloudOcflObjectRootDirIterator(client);
    }

    @Override
    public boolean fileExists(String filePath) {
        try {
            client.head(filePath);
            return true;
        } catch (KeyNotFoundException e) {
            return false;
        }
    }

    @Override
    public InputStream read(String filePath) {
        try {
            return new BufferedInputStream(client.downloadStream(filePath));
        } catch (KeyNotFoundException e) {
            throw new OcflNoSuchFileException(String.format("%s was not found", filePath), e);
        }
    }

    @Override
    public String readToString(String filePath) {
        try {
            return new String(read(filePath).readAllBytes());
        } catch (IOException e) {
            throw OcflIOException.from(e);
        }
    }

    @Override
    public OcflFileRetriever readLazy(String filePath, DigestAlgorithm algorithm, String digest) {
        return fileRetrieverBuilder.build(filePath, algorithm, digest);
    }

    @Override
    public void write(String filePath, byte[] content, String mediaType) {
        failOnExistingFile(filePath);
        client.uploadBytes(filePath, content, mediaType);
    }

    @Override
    public void createDirectories(String path) {
        // no-op
    }

    @Override
    public void copyDirectoryOutOf(String source, Path outputPath) {
        var objects = client.list(withTrailingSlash(source)).getObjects();

        if (objects.isEmpty()) {
            throw new OcflNoSuchFileException(String.format("Directory %s does not exist", source));
        }

        objects.forEach(object -> {
            var destination = outputPath.resolve(object.getKeySuffix());

            UncheckedFiles.createDirectories(destination.getParent());

            try (var stream = client.downloadStream(object.getKey().getPath())) {
                Files.copy(stream, destination);
            } catch (IOException e) {
                throw OcflIOException.from(e);
            }
        });
    }

    @Override
    public void copyFileInto(Path source, String destination, String mediaType) {
        client.uploadFile(source, destination, mediaType);
    }

    @Override
    public void copyFileInternal(String sourceFile, String destinationFile) {
        try {
            client.copyObject(sourceFile, destinationFile);
        } catch (KeyNotFoundException e) {
            throw new OcflNoSuchFileException(String.format("%s was not found", sourceFile), e);
        }
    }

    @Override
    public void moveDirectoryInto(Path source, String destination) {
        failOnExistingDir(destination);

        var objectKeys = Collections.synchronizedList(new ArrayList<String>());

        try (var paths = Files.walk(source)) {
            paths.filter(Files::isRegularFile).forEach(file -> {
                var relative = FileUtil.pathToStringStandardSeparator(source.relativize(file));
                var key = FileUtil.pathJoinFailEmpty(destination, relative);
                client.uploadFile(file, key);
                objectKeys.add(key);
            });
        } catch (IOException | RuntimeException e) {
            client.safeDeleteObjects(objectKeys);

            if (e instanceof IOException) {
                throw OcflIOException.from((IOException) e);
            }
            throw (RuntimeException) e;
        }
    }

    @Override
    public void moveDirectoryInternal(String source, String destination) {
        failOnExistingDir(destination);

        var files = listRecursive(source);

        var srcKeys = new ArrayList<String>();
        var dstKeys = new ArrayList<String>();

        try {
            for (var file : files) {
                if (file.isFile()) {
                    var srcFile = FileUtil.pathJoinIgnoreEmpty(source, file.getRelativePath());
                    var dstFile = FileUtil.pathJoinIgnoreEmpty(destination, file.getRelativePath());
                    client.copyObject(srcFile, dstFile);
                    srcKeys.add(srcFile);
                    dstKeys.add(dstFile);
                }
            }
        } catch (RuntimeException e) {
            client.safeDeleteObjects(dstKeys);
            throw e;
        }

        client.safeDeleteObjects(srcKeys);
    }

    @Override
    public void deleteDirectory(String path) {
        client.deletePath(path);
    }

    @Override
    public void deleteFile(String path) {
        client.deleteObjects(List.of(path));
    }

    @Override
    public void deleteFiles(Collection<String> paths) {
        client.deleteObjects(paths);
    }

    @Override
    public void deleteEmptyDirsDown(String path) {
        // no-op
    }

    @Override
    public void deleteEmptyDirsUp(String path) {
        // no-op
    }

    private void failOnExistingFile(String path) {
        if (fileExists(path)) {
            throw new OcflFileAlreadyExistsException(String.format("File %s already exists", path));
        }
    }

    private void failOnExistingDir(String path) {
        try {
            listDirectory(path);
            throw new OcflFileAlreadyExistsException(String.format("Directory %s already exists", path));
        } catch (OcflNoSuchFileException e) {
            // this is good -- dir does not exist
        }
    }

    private String withTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value;
        }
        return value + "/";
    }

}
