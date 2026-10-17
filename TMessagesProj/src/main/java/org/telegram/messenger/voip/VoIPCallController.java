package org.telegram.messenger.voip;

import android.content.Context;

import org.telegram.tgnet.TLRPC;
import org.webrtc.EglBase;
import org.webrtc.VideoSink;

import java.util.HashSet;

/**
 * The private-call surface consumed by Telegram's existing call UI.
 *
 * Signalling and media ownership stay in the implementing service.  In
 * particular, non-Telegram implementations must not manufacture TL phone call
 * objects merely to drive the UI.
 */
public interface VoIPCallController {

    int CAP_VIDEO = 1;
    int CAP_VIDEO_UPGRADE = 1 << 1;
    int CAP_PIP = 1 << 2;
    int CAP_RETRY = 1 << 3;
    int CAP_RATING = 1 << 4;
    int CAP_CONFERENCE = 1 << 5;
    int CAP_TELEGRAM_EMOJI = 1 << 6;
    int CAP_SCREENCAST = 1 << 7;
    int CAP_PREANSWER_VIDEO = 1 << 8;

    int SECURITY_NONE = 0;
    int SECURITY_DTLS_UNVERIFIED = 1;
    int SECURITY_AUTHENTICATED = 2;
    int SECURITY_TELEGRAM_EMOJI = 3;

    int AUDIO_OUTPUT_SPEAKER = 0;
    int AUDIO_OUTPUT_EARPIECE = 1;
    int AUDIO_OUTPUT_BLUETOOTH = 2;

    interface Listener {
        default void onStateChanged(int state) {}
        default void onSignalBarsCountChanged(int count) {}
        default void onAudioSettingsChanged() {}
        default void onMediaStateUpdated(int audioState, int videoState) {}
        default void onCameraSwitch(boolean isFrontFace) {}
        default void onCameraFirstFrameAvailable() {}
        default void onVideoAvailableChange(boolean isAvailable) {}
        default void onScreenOnChange(boolean screenOn) {}
    }

    int getAccount();
    long getPeerId();
    TLRPC.User getUser();
    boolean isOutgoing();
    int getCallState();
    long getCallDuration();
    boolean isCallingVideo();
    int getCapabilities();
    int getSecurityPresentation();
    String getLastError();

    default boolean hasCapability(int capability) {
        return (getCapabilities() & capability) != 0;
    }

    void addListener(Listener listener);
    void removeListener(Listener listener);

    void acceptIncomingCall();
    void declineIncomingCall();
    void stopRinging();
    void hangUp();
    void hangUp(Runnable onDone);
    default void retry() {}

    boolean isMicMute();
    void setMicMute(boolean mute);
    boolean isBluetoothOn();
    boolean isBluetoothHeadsetConnected();
    boolean isHeadsetPlugged();
    default boolean hasEarpiece() { return true; }
    boolean isSpeakerphoneOn();
    int getAudioOutput();
    void setAudioOutput(int route);
    default String getBluetoothDeviceName() { return null; }
    default void toggleSpeakerphoneOrShowRouteSheet(Context context, boolean fromOverlayWindow, Integer selectedRoute) {
        VoIPCallControllers.showAudioRouteSheet(context, this, fromOverlayWindow, selectedRoute);
    }
    void switchToSpeaker();

    boolean isVideoAvailable();
    int getVideoState();
    int getRemoteVideoState();
    int getRemoteAudioState();
    boolean isScreencast();
    boolean isFrontFaceCamera();
    boolean isSwitchingCamera();
    void createCaptureDevice();
    void clearCamera();
    void requestVideoCall();
    void setVideoState(int state);
    void switchCamera();
    void setSinks(VideoSink local, VideoSink remote);
    boolean isConverting();
    default EglBase.Context getEglContext() { return null; }

    default void playStartRecordSound() {}
    default boolean isTapToVideoTooltipShown() { return true; }
    default void setTapToVideoTooltipShown(boolean shown) {}
    default boolean hasRate() { return false; }
    default void sendCallRating(int rating) {}
    default byte[] getTelegramEncryptionKey() { return null; }
    default byte[] getTelegramGA() { return null; }
    default void convertToConferenceCall(boolean video, HashSet<Long> userIds) {}
}
