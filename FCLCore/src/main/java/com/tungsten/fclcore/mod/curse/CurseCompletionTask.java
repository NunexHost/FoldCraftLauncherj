/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tungsten.fclcore.mod.curse;

import com.google.gson.JsonParseException;
import com.tungsten.fclcore.download.DefaultDependencyManager;
import com.tungsten.fclcore.game.DefaultGameRepository;
import com.tungsten.fclcore.mod.ModManager;
import com.tungsten.fclcore.mod.ModpackCompletionException;
import com.tungsten.fclcore.mod.RemoteMod;
import com.tungsten.fclcore.task.FileDownloadTask;
import com.tungsten.fclcore.task.Task;
import com.tungsten.fclcore.util.Logging;
import com.tungsten.fclcore.util.StringUtils;
import com.tungsten.fclcore.util.gson.JsonUtils;
import com.tungsten.fclcore.util.io.FileUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Complete the CurseForge version.
 */
public final class CurseCompletionTask extends Task<Void> {

    private final DefaultDependencyManager dependency;
    private final DefaultGameRepository repository;
    private final ModManager modManager;
    private final String version;
    private CurseManifest manifest;
    private final List<Task<?>> dependencies = new ArrayList<>();

    private final AtomicBoolean allNameKnown = new AtomicBoolean(true);
    private final AtomicInteger finished = new AtomicInteger(0);
    private final AtomicBoolean notFound = new AtomicBoolean(false);

    /**
     * Constructor.
     *
     * @param dependencyManager the dependency manager.
     * @param version           the existent and physical version.
     */
    public CurseCompletionTask(DefaultDependencyManager dependencyManager, String version) {
        this(dependencyManager, version, null);
    }

    /**
     * Constructor.
     *
     * @param dependencyManager the dependency manager.
     * @param version           the existent and physical version.
     * @param manifest          the CurseForgeModpack manifest.
     */
    public CurseCompletionTask(DefaultDependencyManager dependencyManager, String version, CurseManifest manifest) {
        this.dependency = dependencyManager;
        this.repository = dependencyManager.getGameRepository();
        this.modManager = repository.getModManager(version);
        this.version = version;
        this.manifest = manifest;

        if (manifest == null)
            try {
                File manifestFile = new File(repository.getVersionRoot(version), "manifest.json");
                if (manifestFile.exists())
                    this.manifest = JsonUtils.GSON.fromJson(FileUtils.readText(manifestFile), CurseManifest.class);
            } catch (Exception e) {
                Logging.LOG.log(Level.WARNING, "Unable to read CurseForge modpack manifest.json", e);
            }

        setStage("fcl.modpack.download");
    }

    @Override
    public Collection<Task<?>> getDependencies() {
        return dependencies;
    }

    @Override
    public boolean isRelyingOnDependencies() {
        return false;
    }

    @Override
    public void execute() throws Exception {
        if (manifest == null)
            return;

        File root = repository.getVersionRoot(version);

        // Because in China, Curse is too difficult to visit,
        // if failed, ignore it and retry next time.
        CurseManifest newManifest = manifest.setFiles(
                manifest.getFiles().parallelStream()
                        .map(file -> {
                            updateProgress(finished.incrementAndGet(), manifest.getFiles().size());
                            if (StringUtils.isBlank(file.getFileName()) || file.getUrl() == null) {
                                try {
                                    RemoteMod.File remoteFile = CurseForgeRemoteModRepository.MODS.getModFile(Integer.toString(file.getProjectID()), Integer.toString(file.getFileID()));
                                    return file.withFileName(remoteFile.getFilename()).withURL(remoteFile.getUrl());
                                } catch (FileNotFoundException fof) {
                                    Logging.LOG.log(Level.WARNING, "Could not query api.curseforge.com for deleted mods: " + file.getProjectID() + ", " + file.getFileID(), fof);
                                    notFound.set(true);
                                    return file;
                                } catch (IOException | JsonParseException e) {
                                    Logging.LOG.log(Level.WARNING, "Unable to fetch the file name projectID=" + file.getProjectID() + ", fileID=" + file.getFileID(), e);
                                    allNameKnown.set(false);
                                    return file;
                                }
                            } else {
                                return file;
                            }
                        })
                        .collect(Collectors.toList()));
        FileUtils.writeText(new File(root, "manifest.json"), JsonUtils.GSON.toJson(newManifest));

        File resourcePacks = new File(repository.getVersionRoot(modManager.getVersion()), "resourcepacks");
        for (CurseManifestFile file : newManifest.getFiles())
            if (StringUtils.isNotBlank(file.getFileName())) {
                RemoteMod mod = CurseForgeRemoteModRepository.MODS.getModById(Integer.toString(file.getProjectID()));
                File target;
                if (((CurseAddon) mod.getData()).getClassId() == 12) {
                    target = new File(resourcePacks, file.getFileName());
                    if (target.exists()) {
                        continue;
                    }
                } else {
                    if (modManager.hasSimpleMod(file.getFileName())) {
                        continue;
                    }
                    target = modManager.getSimpleModPath(file.getFileName()).toFile();
                }

                FileDownloadTask task = new FileDownloadTask(file.getUrl(), target);
                task.setCacheRepository(dependency.getCacheRepository());
                task.setCaching(true);
                dependencies.add(task.withCounter("fcl.modpack.download"));
            }

        if (!dependencies.isEmpty()) {
            getProperties().put("total", dependencies.size());
            notifyPropertiesChanged();
        }
    }

    @Override
    public boolean doPostExecute() {
        return true;
    }

    @Override
    public void postExecute() throws Exception {
        // Let this task fail if the curse manifest has not been completed.
        // But continue other downloads.
        if (notFound.get())
            throw new ModpackCompletionException(new FileNotFoundException());
        if (!allNameKnown.get() || !isDependenciesSucceeded())
            throw new ModpackCompletionException();
    }
}
