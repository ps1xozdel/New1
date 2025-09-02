package eu.siacs.conversations.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

public class ColorPickerPreference extends Preference {
    public ColorPickerPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWidgetLayoutResource(android.R.layout.simple_list_item_1);
    }

    @Override
    protected void onClick() {
        super.onClick();
        // Пример простого диалога выбора цвета
        final String[] colors = {"#FFFFFF", "#000000", "#FF0000", "#00FF00", "#0000FF"};
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(getTitle());
        builder.setItems(colors, (dialog, which) -> {
            String selectedColor = colors[which];
            persistString(selectedColor);
            setSummary(selectedColor);
            saveColor(selectedColor);
            Toast.makeText(getContext(), "Выбран цвет: " + selectedColor, Toast.LENGTH_SHORT).show();
        });
        builder.show();
    }

    @Override
    protected void onSetInitialValue(boolean restorePersistedValue, Object defaultValue) {
        String value = restorePersistedValue ? getPersistedString("#FFFFFF") : (String) defaultValue;
        setSummary(value);
    }

    private void saveColor(String color) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        prefs.edit().putString(getKey(), color).apply();
    }

    public static int getSavedColor(Context context, String key, int defaultColor) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String color = prefs.getString(key, null);
        if (color != null) {
            try {
                return Color.parseColor(color);
            } catch (Exception e) {
                return defaultColor;
            }
        }
        return defaultColor;
    }
}
