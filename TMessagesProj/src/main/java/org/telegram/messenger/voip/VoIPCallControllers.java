package org.telegram.messenger.voip;

import android.content.Context;
import android.os.Build;
import android.view.WindowManager;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

/** Process-wide arbitration for the single private call shown by the call UI. */
public final class VoIPCallControllers {

    private static VoIPCallController active;

    private VoIPCallControllers() {}

    public static synchronized VoIPCallController get() {
        return active;
    }

    public static synchronized boolean register(VoIPCallController controller) {
        if (controller == null) {
            throw new NullPointerException("controller");
        }
        if (active != null && active != controller) {
            return false;
        }
        active = controller;
        return true;
    }

    public static synchronized void unregister(VoIPCallController controller) {
        if (active == controller) {
            active = null;
        }
    }

    public static synchronized boolean isActive(VoIPCallController controller) {
        return active == controller;
    }

    public static void showAudioRouteSheet(Context context, VoIPCallController controller, boolean fromOverlayWindow, Integer selectedRoute) {
        if (context == null || controller == null || !isActive(controller)) {
            return;
        }
        if (controller.isBluetoothHeadsetConnected() && controller.hasEarpiece()) {
            BottomSheet.Builder builder = new BottomSheet.Builder(context)
                    .setTitle(LocaleController.getString(R.string.VoipOutputDevices), true)
                    .selectedPos(selectedRoute)
                    .setCellType(selectedRoute != null ? BottomSheet.Builder.CELL_TYPE_CALL : 0)
                    .setItems(new CharSequence[]{
                                    LocaleController.getString(R.string.VoipAudioRoutingSpeaker),
                                    controller.isHeadsetPlugged()
                                            ? LocaleController.getString(R.string.VoipAudioRoutingHeadset)
                                            : LocaleController.getString(R.string.VoipAudioRoutingEarpiece),
                                    controller.getBluetoothDeviceName() != null
                                            ? controller.getBluetoothDeviceName()
                                            : LocaleController.getString(R.string.VoipAudioRoutingBluetooth)},
                            new int[]{R.drawable.msg_call_speaker,
                                    controller.isHeadsetPlugged() ? R.drawable.calls_menu_headset : R.drawable.msg_call_earpiece,
                                    R.drawable.msg_call_bluetooth},
                            (dialog, which) -> {
                                if (isActive(controller)) {
                                    controller.setAudioOutput(which);
                                }
                            });
            BottomSheet sheet = builder.create();
            sheet.setOnShowListener(dialog -> {
                for (int i = 0; i < sheet.getItemViews().size(); i++) {
                    sheet.setItemColor(i, Theme.getColor(Theme.key_dialogTextBlack), Theme.getColor(Theme.key_dialogTextBlack));
                }
                if (selectedRoute != null) {
                    int color = Theme.getColor(Theme.key_dialogTextLink);
                    sheet.setItemColor(selectedRoute, color, color);
                }
            });
            if (fromOverlayWindow) {
                if (Build.VERSION.SDK_INT >= 26) {
                    sheet.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                } else {
                    sheet.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
                }
            }
            builder.show();
            return;
        }

        int current = controller.getAudioOutput();
        if (controller.hasEarpiece()) {
            controller.setAudioOutput(current == VoIPCallController.AUDIO_OUTPUT_SPEAKER
                    ? VoIPCallController.AUDIO_OUTPUT_EARPIECE
                    : VoIPCallController.AUDIO_OUTPUT_SPEAKER);
        } else {
            controller.setAudioOutput(current == VoIPCallController.AUDIO_OUTPUT_BLUETOOTH
                    ? VoIPCallController.AUDIO_OUTPUT_SPEAKER
                    : VoIPCallController.AUDIO_OUTPUT_BLUETOOTH);
        }
    }
}
