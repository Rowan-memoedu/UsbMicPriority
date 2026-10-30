package io.memoedu.usbmicpriority;

import android.content.AttributionSource;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import java.lang.reflect.Method;
import java.util.List;

/** Independent local VoIP canary; no calls, networking, or microphone sample reads. */
public final class CommunicationCanary {
    public static void main(String[] args) {
        int result = 0;
        try { run(); } catch (Throwable failure) { failure.printStackTrace(); result = 1; }
        System.exit(result);
    }
    private static void run() throws Exception {
        Looper.prepareMainLooper();
        Object audioBinder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "audio");
        Object service = Class.forName("android.media.IAudioService$Stub").getMethod("asInterface", IBinder.class).invoke(null, audioBinder);
        Class<?> api = Class.forName("android.media.IAudioService");
        Class<?> attrs = Class.forName("android.media.AudioDeviceAttributes");
        Method type = attrs.getMethod("getType");
        List<?> preferences = (List<?>) api.getMethod("getPreferredDevicesForCapturePreset", int.class).invoke(service, 7);
        if (preferences.size() != 1) throw new IllegalStateException("Exactly one global voice input preference required");
        Object preferred = preferences.get(0);
        int expectedInput = (Integer) type.invoke(preferred);
        if (expectedInput != 11 && expectedInput != 12 && expectedInput != 22) throw new IllegalStateException("Global voice input is not USB");
        String expectedAddress = (String) attrs.getMethod("getAddress").invoke(preferred);
        for (AudioDeviceInfo input : (AudioDeviceInfo[]) AudioManager.class.getDeclaredMethod("getDevicesStatic", int.class).invoke(null, 1)) {
            if (input.getType() == 7 || input.getType() == 26) throw new IllegalStateException("Bluetooth microphone remains selectable");
        }
        if ((Integer) api.getMethod("getMode").invoke(service) != 0) throw new IllegalStateException("Existing communication session: leave it untouched");
        Object bluetooth = null;
        for (Object device : (List<?>) api.getMethod("getAvailableCommunicationDevices").invoke(service)) {
            int candidateType = (Integer) type.invoke(device);
            if (candidateType == 7 || candidateType == 26) { bluetooth = device; break; }
        }
        if (bluetooth == null) throw new IllegalStateException("No Bluetooth communication output available");
        int expectedOutput = (Integer) type.invoke(bluetooth);
        Method setMode = api.getMethod("setMode", int.class, IBinder.class, String.class);
        Method setComm = api.getMethod("setCommunicationDevice", IBinder.class, attrs, AttributionSource.class);
        Binder token = new Binder();
        AttributionSource attribution = new AttributionSource.Builder(Process.myUid())
                .setPid(Process.myPid()).setPackageName("com.android.shell").build();
        AudioRecord record = null;
        AudioTrack track = null;
        boolean modeChanged = false;
        boolean outputChanged = false;
        try {
            setMode.invoke(service, 3, token, "com.android.shell"); modeChanged = true;
            if (!Boolean.TRUE.equals(setComm.invoke(service, token, bluetooth, attribution))) throw new IllegalStateException("Bluetooth communication output rejected");
            outputChanged = true;
            record = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(64000).build();
            record.startRecording();
            track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(64000).setTransferMode(AudioTrack.MODE_STREAM).build();
            track.play();
            int stable = 0;
            short[] silence = new short[8000];
            for (int sample = 0; sample < 10; sample++) {
                track.write(silence, 0, silence.length);
                Thread.sleep(200);
                AudioDeviceInfo input = record.getRoutedDevice();
                AudioDeviceInfo output = track.getRoutedDevice();
                boolean matched = input != null && output != null && input.getType() == expectedInput
                        && expectedAddress.equals(input.getAddress()) && output.getType() == expectedOutput;
                System.out.println("sample=" + sample + " inputType=" + (input == null ? -1 : input.getType())
                        + " outputType=" + (output == null ? -1 : output.getType()));
                stable = matched ? stable + 1 : 0;
                if (stable >= 3) { System.out.println("PASS USB input + Bluetooth communication output; Bluetooth microphones not selectable"); return; }
            }
            throw new IllegalStateException("USB input + Bluetooth output did not stabilize");
        } finally {
            if (track != null) { try { track.stop(); } finally { track.release(); } }
            if (record != null) { try { record.stop(); } finally { record.release(); } }
            if (outputChanged) setComm.invoke(service, token, null, attribution);
            if (modeChanged) setMode.invoke(service, 0, token, "com.android.shell");
        }
    }
}
