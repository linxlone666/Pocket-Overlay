package app.pocketoverlay;

import android.graphics.Rect;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Locates ClusterTune's visible, user-enabled profile picker handle on the primary display. */
final class ClusterTuneShortcut {
    // Keep the command short for the firmware service's command buffer.
    private static final String HANDLE_FRAME =
            "dumpsys -t 1 window 'ClusterTune profile edge handle'|awk '"
            + "/^  Window #/{d=p=0;f=\"\"}/mDisplayId=0 /{d=1}/package=com[.]aure[.]clustertune /{p=1}"
            + "/Frames:/{f=substr($4,7)}/isVisible=true/&&d&&p&&f!=\"\"{print f;exit}'";
    private static final Pattern FRAME = Pattern.compile("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]");

    private static final String PICKER_VISIBLE =
            "dumpsys -t 1 window 'ClusterTune overlay'|awk '"
            + "/^  Window #/{p=d=n=0}/package=com[.]aure[.]clustertune /{p=1}/mDisplayId=0 /{d=1}/fl=NOT_FOCUSABLE/{n=1}"
            + "/isVisible=true/&&p&&d&&n{print 1;exit}'";

    private ClusterTuneShortcut() {}

    static boolean isPickerVisible(RetroidFirmware firmware) {
        return "1".equals(firmware.query(PICKER_VISIBLE));
    }

    static Rect findHandle() {
        Matcher frame = FRAME.matcher(new RetroidFirmware().query(HANDLE_FRAME));
        if (!frame.matches()) {
            return null;
        }
        return new Rect(Integer.parseInt(frame.group(1)), Integer.parseInt(frame.group(2)),
                Integer.parseInt(frame.group(3)), Integer.parseInt(frame.group(4)));
    }
}
