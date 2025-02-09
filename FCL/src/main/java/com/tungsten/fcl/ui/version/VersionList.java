package com.tungsten.fcl.ui.version;

import static com.tungsten.fclcore.download.LibraryAnalyzer.LibraryType.MINECRAFT;
import static com.tungsten.fclcore.util.Logging.LOG;

import android.content.Context;
import android.view.View;
import android.widget.ListView;

import com.google.gson.JsonParseException;
import com.tungsten.fcl.R;
import com.tungsten.fcl.game.FCLGameRepository;
import com.tungsten.fcl.setting.Profile;
import com.tungsten.fcl.setting.Profiles;
import com.tungsten.fcl.util.AndroidUtils;
import com.tungsten.fclcore.download.LibraryAnalyzer;
import com.tungsten.fclcore.fakefx.beans.binding.Bindings;
import com.tungsten.fclcore.mod.ModpackConfiguration;
import com.tungsten.fclcore.task.Schedulers;
import com.tungsten.fcllibrary.component.view.FCLButton;
import com.tungsten.fcllibrary.component.view.FCLProgressBar;
import com.tungsten.fcllibrary.component.view.FCLTextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class VersionList {

    private final Context context;
    private final ListView listView;
    private final FCLButton refreshButton;
    private final FCLProgressBar progressBar;
    private final FCLTextView dirPath;

    public VersionList(Context context, ListView listView, FCLButton refreshButton, FCLProgressBar progressBar, FCLTextView dirPath) {
        this.context = context;
        this.listView = listView;
        this.refreshButton = refreshButton;
        this.progressBar = progressBar;
        this.dirPath = dirPath;

        Profiles.registerVersionsListener(this::loadVersions);
    }

    private void loadVersions(Profile profile) {
        Schedulers.androidUIThread().execute(() -> {
            dirPath.setText(profile.getGameDir().getAbsolutePath());
            refreshButton.setEnabled(false);
            listView.setVisibility(View.GONE);
            progressBar.setVisibility(View.VISIBLE);
        });
        FCLGameRepository repository = profile.getRepository();
        new Thread(() -> {
            if (profile == Profiles.getSelectedProfile()) {
                List<VersionListItem> children = repository.getDisplayVersions()
                        .map(version -> {
                            String game = profile.getRepository().getGameVersion(version.getId()).orElse(context.getString(R.string.message_unknown));
                            StringBuilder libraries = new StringBuilder(game);
                            LibraryAnalyzer analyzer = LibraryAnalyzer.analyze(repository.getResolvedPreservingPatchesVersion(version.getId()));
                            for (LibraryAnalyzer.LibraryMark mark : analyzer) {
                                String libraryId = mark.getLibraryId();
                                String libraryVersion = mark.getLibraryVersion();
                                if (libraryId.equals(MINECRAFT.getPatchId())) continue;
                                if (AndroidUtils.hasStringId(context, "install_installer_" + libraryId.replace("-", "_"))) {
                                    libraries.append(", ").append(AndroidUtils.getLocalizedText(context, "install_installer_" + libraryId.replace("-", "_")));
                                    if (libraryVersion != null)
                                        libraries.append(": ").append(libraryVersion.replaceAll("(?i)" + libraryId, ""));
                                }
                            }
                            String tag = null;
                            try {
                                ModpackConfiguration<?> config = profile.getRepository().readModpackConfiguration(version.getId());
                                if (config != null)
                                    tag = config.getVersion();
                            } catch (IOException | JsonParseException e) {
                                LOG.log(Level.WARNING, "Failed to read modpack configuration from " + version, e);
                            }
                            return new VersionListItem(profile, version.getId(), libraries.toString(), tag, repository.getVersionIconImage(version.getId()));
                        })
                        .collect(Collectors.toList());
                if (profile == Profiles.getSelectedProfile()) {
                    Schedulers.androidUIThread().execute(() -> {
                        VersionListAdapter adapter = new VersionListAdapter(context, (ArrayList<VersionListItem>) children);
                        listView.setAdapter(adapter);
                        refreshButton.setEnabled(true);
                        listView.setVisibility(View.VISIBLE);
                        progressBar.setVisibility(View.GONE);
                    });

                    children.forEach(it -> it.selectedProperty().bind(Bindings.createBooleanBinding(() -> profile.selectedVersionProperty().get().equals(it.getVersion()), profile.selectedVersionProperty())));
                }
            }
        }).start();
    }

    public void refreshList() {
        Profiles.getSelectedProfile().getRepository().refreshVersionsAsync().start();
    }
}