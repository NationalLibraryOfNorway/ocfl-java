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

package io.ocfl.core.storage;

import io.ocfl.api.exception.OcflStateException;
import io.ocfl.api.model.OcflVersion;
import io.ocfl.api.util.Enforce;
import io.ocfl.core.extension.ExtensionSupportEvaluator;
import io.ocfl.core.extension.OcflExtensionConfig;
import io.ocfl.core.inventory.InventoryMapper;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OcflStorage abstract implementation that handles managing the repository's state, initialized, open, close.
 */
public abstract class AbstractOcflStorage implements OcflStorage {

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    protected InventoryMapper inventoryMapper;
    protected ExtensionSupportEvaluator supportEvaluator;
    private RepositoryConfig repositoryConfig;

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized RepositoryConfig initializeStorage(
            OcflVersion ocflVersion,
            OcflExtensionConfig layoutConfig,
            InventoryMapper inventoryMapper,
            ExtensionSupportEvaluator supportEvaluator) {
        if (this.initialized.get()) {
            return this.repositoryConfig;
        }

        this.inventoryMapper = Enforce.notNull(inventoryMapper, "inventoryMapper cannot be null");
        this.supportEvaluator = Enforce.notNull(supportEvaluator, "supportEvaluator cannot be null");

        this.repositoryConfig = doInitialize(ocflVersion, layoutConfig);
        this.initialized.set(true);
        return repositoryConfig;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        closed.set(true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void invalidateCache(String objectId) {
        // no op
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void invalidateCache() {
        // no op
    }

    /**
     * Does whatever is necessary to initialize OCFL repository storage.
     *
     * @param ocflVersion the OCFL version, may be null to default to version in storage root
     * @param layoutConfig the storage layout configuration, may be null to auto-detect existing configuration
     */
    protected abstract RepositoryConfig doInitialize(OcflVersion ocflVersion, OcflExtensionConfig layoutConfig);

    /**
     * Throws an exception if the repository has not been initialized or is closed
     */
    protected void ensureOpen() {
        if (closed.get()) {
            throw new OcflStateException(this.getClass().getName() + " is closed.");
        }

        if (!initialized.get()) {
            throw new OcflStateException(this.getClass().getName() + " must be initialized before it can be used.");
        }
    }
}