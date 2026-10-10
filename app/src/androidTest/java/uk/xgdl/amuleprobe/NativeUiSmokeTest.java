package uk.xgdl.amuleprobe;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Checks real menu navigation and language switching against the packaged app. */
@RunWith(AndroidJUnit4.class)
public final class NativeUiSmokeTest {
    private static final String PACKAGE = "uk.xgdl.amuleprobe";
    private static final long STARTUP_TIMEOUT_MS = 90000;
    private static final long UI_TIMEOUT_MS = 15000;

    private UiDevice device;
    private Context target;
    private String previousLanguage;

    @Before
    public void setUp() {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        SharedPreferences settings = target.getSharedPreferences("native_ui", Context.MODE_PRIVATE);
        previousLanguage = settings.getString("language", "English");
        settings.edit().putString("language", "English").commit();

        Intent launch = target.getPackageManager().getLaunchIntentForPackage(PACKAGE);
        assertNotNull("Native app launch intent", launch);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        target.startActivity(launch);
        assertTrue("Native interface did not open", device.wait(
                Until.hasObject(By.res(PACKAGE, "native_page_title")), STARTUP_TIMEOUT_MS));
    }

    @After
    public void restoreLanguage() {
        if (target != null && previousLanguage != null) {
            target.getSharedPreferences("native_ui", Context.MODE_PRIVATE).edit()
                    .putString("language", previousLanguage).commit();
        }
    }

    @Test
    public void navigatesAllNativeScreensAndChecksAllLanguages() {
        String[][] languages = {
                {"English", "Networks", "Search", "Downloads", "Shared", "Clients", "Messages", "Statistics", "Preferences", "About", "Appearance"},
                {"Español", "Redes", "Buscar", "Descargas", "Compartidos", "Clientes", "Mensajes", "Estadísticas", "Preferencias", "Acerca de", "Apariencia"},
                {"简体中文", "网络", "搜索", "下载", "共享", "客户端", "消息", "统计", "设置", "关于", "外观"},
                {"हिंदी", "नेटवर्क", "खोज", "डाउनलोड", "साझा", "ग्राहकों", "संदेशों", "आंकड़े", "प्राथमिकताएँ", "के बारे में", "उपस्थिति"}
        };

        for (int i = 1; i <= 9; i++) navigate(languages[0][i]);
        navigate("Preferences");
        choose(R.id.native_preferences_section, "Appearance");
        NativeStrings strings = new NativeStrings(target);
        for (int i = 0; i < languages.length; i++) {
            String[] language = languages[i];
            if (i > 0) choose(R.id.native_language, language[0]);
            assertTitle(language[8]);
            assertEquals(language[0], target.getSharedPreferences("native_ui", Context.MODE_PRIVATE)
                    .getString("language", ""));
            for (int page = 1; page <= 9; page++) {
                assertEquals("Navigation translation for " + language[0], language[page],
                        strings.translate(languages[0][page], language[0]));
            }
        }
        // The loop leaves the app in Hindi; restore English before @After restores the user's setting.
        choose(R.id.native_language, "English");
        assertTitle("Preferences");
    }

    private void navigate(String label) {
        findResource("native_menu").click();
        UiObject2 item = device.wait(Until.findObject(By.text(label)), UI_TIMEOUT_MS);
        assertNotNull("Navigation item: " + label, item);
        item.click();
        assertTitle(label);
    }

    private void choose(int resourceId, String label) {
        UiObject2 spinner = device.wait(Until.findObject(By.res(PACKAGE,
                target.getResources().getResourceEntryName(resourceId))), UI_TIMEOUT_MS);
        assertNotNull("Preference selector: " + label, spinner);
        spinner.click();
        UiObject2 choices = device.wait(Until.findObject(By.clazz("android.widget.ListView")), UI_TIMEOUT_MS);
        assertNotNull("Preference choices", choices);
        UiObject2 option = choices.findObject(By.text(label));
        assertNotNull("Preference option: " + label, option);
        option.click();
    }

    private UiObject2 findResource(String name) {
        UiObject2 object = device.wait(Until.findObject(By.res(PACKAGE, name)), UI_TIMEOUT_MS);
        assertNotNull("Native view: " + name, object);
        return object;
    }

    private void assertTitle(String expected) {
        assertTrue("Native screen did not show title: " + expected, device.wait(
                Until.hasObject(By.res(PACKAGE, "native_page_title").text(expected)), UI_TIMEOUT_MS));
    }
}
