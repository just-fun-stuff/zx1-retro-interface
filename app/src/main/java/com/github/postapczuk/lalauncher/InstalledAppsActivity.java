/*
 * Light Android Launcher
 * Modified 2026-07-17: late-1990s program index for the NW-ZX1.
 * Licensed under GPLv2; see LICENSE.
 */
package com.github.postapczuk.lalauncher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class InstalledAppsActivity extends Activity {

    private final List<AppEntry> allApps = new ArrayList<AppEntry>();
    private final List<AppEntry> visibleApps = new ArrayList<AppEntry>();
    private AppAdapter adapter;
    private Skin skin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_installed);
        skin = Skin.of(this);

        ListView list = (ListView) findViewById(R.id.app_list);
        EditText search = (EditText) findViewById(R.id.search_filter);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        loadApps();
        filter("");

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filter(s.toString());
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                launchApp(visibleApps.get(position));
            }
        });

        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                showAppInfo(visibleApps.get(position));
                return true;
            }
        });

        findViewById(R.id.action_home).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { finish(); }
        });

        GearView gear = (GearView) findViewById(R.id.action_settings);
        gear.setInk(skin.panelDim);
        gear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(InstalledAppsActivity.this, SettingsActivity.class));
                overridePendingTransition(R.anim.slide_up, android.R.anim.fade_out);
            }
        });

        applySkin();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The SETTINGS screen sits above this one; a style change there is
        // reconciled the moment this screen shows again.
        Skin.restyleIfChanged(this, skin);
    }

    /** TUBE AMP re-tint; on LCD the inflated resources already match. */
    private void applySkin() {
        if (skin.isLcd()) {
            return;
        }
        findViewById(R.id.program_root).setBackgroundColor(skin.chassis);
        skin.tintDrawerControl((TextView) findViewById(R.id.action_home));
        ((TextView) findViewById(R.id.program_title)).setTextColor(skin.panelText);
        ((TextView) findViewById(R.id.program_count)).setTextColor(skin.panelDim);
        findViewById(R.id.action_settings).setBackground(skin.drawerControlBackground());
        findViewById(R.id.program_glass).setBackground(skin.frameBackground());
        skin.tintSearch((EditText) findViewById(R.id.search_filter));
        skin.tintList((ListView) findViewById(R.id.app_list));
        ((TextView) findViewById(R.id.program_hint)).setTextColor(skin.ink);
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN, null);
        query.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> activities = pm.queryIntentActivities(query, 0);
        Collections.sort(activities, new ResolveInfo.DisplayNameComparator(pm));
        allApps.clear();
        for (ResolveInfo info : activities) {
            if (!getPackageName().equals(info.activityInfo.packageName)) {
                allApps.add(new AppEntry(
                        info.loadLabel(pm).toString(),
                        info.activityInfo.packageName));
            }
        }
    }

    private void filter(String query) {
        String normalized = query.trim().toLowerCase(Locale.US);
        visibleApps.clear();
        for (AppEntry app : allApps) {
            if (normalized.length() == 0 || app.label.toLowerCase(Locale.US).contains(normalized)) {
                visibleApps.add(app);
            }
        }
        adapter.notifyDataSetChanged();
        ((TextView) findViewById(R.id.program_count)).setText(
                String.format(Locale.US, "%02d PROGRAMS", visibleApps.size()));
    }

    private void launchApp(AppEntry entry) {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(entry.packageName);
            if (intent == null) {
                throw new ActivityNotFoundException();
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, R.string.action_unavailable, Toast.LENGTH_SHORT).show();
            loadApps();
            filter("");
        }
    }

    private void showAppInfo(AppEntry entry) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + entry.packageName));
            startActivity(intent);
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, R.string.action_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(android.R.anim.fade_in, R.anim.slide_down);
    }

    private static final class AppEntry {
        final String label;
        final String packageName;

        AppEntry(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }
    }

    private final class AppAdapter extends ArrayAdapter<AppEntry> {
        AppAdapter() {
            super(InstalledAppsActivity.this, R.layout.app_row, visibleApps);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = getLayoutInflater().inflate(R.layout.app_row, parent, false);
            }
            AppEntry app = visibleApps.get(position);
            TextView index = (TextView) row.findViewById(R.id.app_index);
            TextView label = (TextView) row.findViewById(R.id.app_label);
            index.setText(String.format(Locale.US, "%02d", position + 1));
            label.setText(app.label.toUpperCase(Locale.US));
            if (!skin.isLcd()) {
                index.setTextColor(skin.inkDim);
                label.setTextColor(skin.ink);
                ((TextView) row.findViewById(R.id.app_chevron)).setTextColor(skin.inkDim);
            }
            return row;
        }
    }
}
