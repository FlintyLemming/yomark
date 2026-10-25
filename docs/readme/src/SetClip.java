import android.content.ClipData;
import android.os.IBinder;
import java.lang.reflect.Method;

/**
 * 往模拟器的剪贴板里放一段文字，capture.ps1 拿它往输入框里填中文（adb shell input text 打不了中文）。
 *
 * 用 app_process 以 shell 身份跑，直接调剪贴板服务（scrcpy 也是这么做的）：
 *
 *     adb shell CLASSPATH=/data/local/tmp/setclip.dex app_process / SetClip <文字的 UTF-8 十六进制>
 *
 * 参数用十六进制传，免得中文在 adb shell 的命令行里被转坏。
 */
public class SetClip {
    public static void main(String[] args) throws Exception {
        String hex = args[0];
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        String text = new String(bytes, "UTF-8");

        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "clipboard");
        Object clipboard = Class.forName("android.content.IClipboard$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        ClipData clip = ClipData.newPlainText(null, text);

        // 各版本的参数不一样：Android 14 起是 (clip, callingPackage, attributionTag, userId, deviceId)
        for (Method m : clipboard.getClass().getMethods()) {
            if (!m.getName().equals("setPrimaryClip")) continue;
            Class<?>[] types = m.getParameterTypes();
            Object[] values = new Object[types.length];
            values[0] = clip;
            boolean packageSet = false;
            for (int i = 1; i < types.length; i++) {
                if (types[i] == String.class && !packageSet) {
                    values[i] = "com.android.shell";
                    packageSet = true;
                } else if (types[i] == int.class) {
                    values[i] = 0;
                }
            }
            m.invoke(clipboard, values);
            return;
        }
        throw new IllegalStateException("IClipboard 里没有 setPrimaryClip");
    }
}
