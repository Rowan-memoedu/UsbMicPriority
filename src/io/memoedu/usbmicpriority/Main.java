package io.memoedu.usbmicpriority;

import android.media.AudioDeviceInfo;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.system.Os;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** ADB-activated local service. No network listeners, microphone reads, or installed APK. */
public final class Main {
    private static final File ROOT = new File("/data/local/tmp/usb-mic-priority");
    private static final File STATUS = new File(ROOT, "status.json");
    private static final File JOURNAL = new File(ROOT, "journal.bin");
    private static final File STOP = new File(ROOT, "stop.request");

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            if (Process.myUid() != 2000 && Process.myUid() != 0) {
                throw new IllegalStateException("Run through an authorized adb shell");
            }
            if (Looper.myLooper() == null) Looper.prepareMainLooper();
            if (!ROOT.isDirectory() && !ROOT.mkdirs()) throw new IllegalStateException("Cannot create service data directory");
            Os.chmod(ROOT.getPath(), 0700);
            String action = args.length == 0 ? "status" : args[0];
            if (action.equals("status")) {
                System.out.println(readStatus().toString());
            } else if (action.equals("stop")) {
                if (!readStatus().optBoolean("processAlive")) {
                    withLock(false);
                } else {
                    atomicWrite(STOP, new byte[]{1});
                    for (int i = 0; i < 60 && readStatus().optBoolean("processAlive"); i++) Thread.sleep(100);
                    JSONObject result = readStatus();
                    System.out.println(result);
                    if (result.optBoolean("processAlive") || result.optBoolean("recoveryRequired")) exitCode = 2;
                }
            } else if (action.equals("run")) {
                withLock(true);
            } else if (action.equals("recover")) {
                withLock(false);
            } else if (action.equals("inspect")) {
                Platform api = new Platform();
                JSONObject info = new JSONObject();
                JSONArray devices = new JSONArray();
                for (RoutingController.Device device : api.devices()) {
                    devices.put(new JSONObject().put("input", device.input).put("type", device.type).put("name", device.name));
                }
                info.put("devices", devices);
                JSONObject preferences = new JSONObject();
                for (int source : RoutingController.SOURCES) {
                    JSONArray types = new JSONArray();
                    for (RoutingController.Device device : api.preferred(source)) types.put(device.type);
                    preferences.put(String.valueOf(source), types);
                }
                System.out.println(info.put("preferredInputTypes", preferences));
            } else {
                throw new IllegalArgumentException("Expected run, status, stop, recover or inspect");
            }
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    private static void withLock(boolean run) throws Exception {
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(ROOT, "service.lock"), "rw")) {
            FileLock lock = lockFile.getChannel().tryLock();
            if (lock == null) throw new IllegalStateException("Service is running; request stop before recovery or replacement");
            try {
                Platform api = new Platform();
                DiskJournal journal = new DiskJournal(api);
                RoutingController controller = new RoutingController(api, journal, journal.load());
                controller.restore(); // recover any interrupted older instance before starting a new policy session
                if (!run) {
                    writeStatus("STOPPED", false, controller, "");
                    System.out.println(readStatus());
                    return;
                }
                if (STOP.exists() && !STOP.delete()) throw new IllegalStateException("Cannot clear old stop request");
                String previous = "";
                long lastReport = 0;
                String failureMessage = "";
                try {
                    while (!STOP.exists()) {
                        String state = controller.reconcile();
                        long now = System.currentTimeMillis();
                        String fingerprint = state + ":" + controller.selectedName() + ":" + controller.state.hidden.size();
                        if (!fingerprint.equals(previous) || now - lastReport > 60000) {
                            writeStatus(state, true, controller, "");
                            if (!fingerprint.equals(previous)) System.out.println(state);
                            previous = fingerprint;
                            lastReport = now;
                        }
                        Thread.sleep(1000);
                    }
                } catch (Exception failure) {
                    failureMessage = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                    System.err.println(failureMessage);
                } finally {
                    try {
                        controller.restore();
                        writeStatus(failureMessage.isEmpty() ? "STOPPED" : "ERROR_RESTORED", false, controller, failureMessage);
                    } catch (Exception restoreFailure) {
                        writeStatus("RECOVERY_REQUIRED", false, controller,
                                failureMessage + "; " + restoreFailure.getClass().getSimpleName() + ": " + restoreFailure.getMessage());
                        throw restoreFailure;
                    }
                }
            } finally { lock.release(); }
        }
    }

    private static JSONObject readStatus() throws Exception {
        JSONObject info = STATUS.isFile()
                ? new JSONObject(new String(Files.readAllBytes(STATUS.toPath()), StandardCharsets.UTF_8))
                : new JSONObject().put("state", "NOT_STARTED").put("running", false);
        int pid = info.optInt("pid", -1);
        boolean alive = false;
        if (pid > 0 && pid != Process.myPid()) {
            try {
                String command = new String(Files.readAllBytes(new File("/proc/" + pid + "/cmdline").toPath()), StandardCharsets.UTF_8);
                alive = command.contains("io.memoedu.usbmicpriority.Main") && command.contains("run");
            } catch (Exception ignored) { }
        }
        info.put("processAlive", alive);
        info.put("recoveryRequired", JOURNAL.isFile() && !alive && info.optBoolean("dirty"));
        if (info.optBoolean("running") && !alive) {
            info.put("lastState", info.optString("state"));
            info.put("state", info.optBoolean("recoveryRequired") ? "RECOVERY_REQUIRED" : "STOPPED");
        }
        info.put("running", alive && info.optBoolean("running"));
        return info;
    }

    private static void writeStatus(String state, boolean running, RoutingController controller, String error) throws Exception {
        JSONObject info = new JSONObject().put("state", state).put("running", running)
                .put("pid", Process.myPid()).put("updatedAt", System.currentTimeMillis())
                .put("usbInput", controller.selectedName()).put("hiddenBluetoothInputs", controller.state.hidden.size())
                .put("dirty", controller.state.dirty()).put("error", error);
        atomicWrite(STATUS, info.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void atomicWrite(File file, byte[] bytes) throws Exception {
        File temporary = new File(file.getPath() + ".tmp");
        try (RandomAccessFile out = new RandomAccessFile(temporary, "rw")) {
            out.setLength(0);
            out.write(bytes);
            out.getFD().sync();
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static final class Platform implements RoutingController.Platform {
        final Class<?> attributes = Class.forName("android.media.AudioDeviceAttributes");
        final Class<?> audioSystem = Class.forName("android.media.AudioSystem");
        final Class<?> audioApi = Class.forName("android.media.IAudioService");
        final Object service;
        final Constructor<?> deviceInfo;
        Platform() throws Exception {
            Class<?> binder = Class.forName("android.os.IBinder");
            Object audioBinder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "audio");
            service = Class.forName("android.media.IAudioService$Stub").getMethod("asInterface", binder).invoke(null, audioBinder);
            deviceInfo = AudioDeviceInfo.class.getConstructor(Class.forName("android.media.AudioDevicePort"));
        }
        RoutingController.Device device(Object payload) throws Exception {
            return new RoutingController.Device((Integer) attributes.getMethod("getRole").invoke(payload) == 1,
                    (Integer) attributes.getMethod("getType").invoke(payload),
                    (String) attributes.getMethod("getAddress").invoke(payload),
                    (String) attributes.getMethod("getName").invoke(payload), payload);
        }
        @Override public List<RoutingController.Device> devices() throws Exception {
            ArrayList<Object> ports = new ArrayList<>();
            int[] generation = new int[1];
            check((Integer) audioSystem.getMethod("listAudioPorts", ArrayList.class, int[].class).invoke(null, ports, generation), "list audio ports");
            List<RoutingController.Device> devices = new ArrayList<>();
            Class<?> portType = Class.forName("android.media.AudioDevicePort");
            for (Object port : ports) {
                if (portType.isInstance(port)) {
                    AudioDeviceInfo info = (AudioDeviceInfo) deviceInfo.newInstance(port);
                    Object payload = attributes.getConstructor(AudioDeviceInfo.class).newInstance(info);
                    devices.add(device(payload));
                }
            }
            return devices;
        }
        @Override public List<RoutingController.Device> preferred(int source) throws Exception {
            List<?> payloads = (List<?>) audioApi.getMethod("getPreferredDevicesForCapturePreset", int.class).invoke(service, source);
            List<RoutingController.Device> devices = new ArrayList<>();
            for (Object payload : payloads) devices.add(device(payload));
            return devices;
        }
        @Override public void prefer(int source, List<RoutingController.Device> devices) throws Exception {
            if (devices.isEmpty()) {
                check((Integer) audioApi.getMethod("clearPreferredDevicesForCapturePreset", int.class).invoke(service, source), "clear capture preference " + source);
            } else {
                List<Object> payloads = new ArrayList<>();
                for (RoutingController.Device device : devices) payloads.add(device.payload);
                check((Integer) audioApi.getMethod("setPreferredDevicesForCapturePreset", int.class, List.class).invoke(service, source, payloads), "set capture preference " + source);
            }
        }
        @Override public void connection(RoutingController.Device device, boolean available) throws Exception {
            if (!device.input || !device.isBluetooth()) throw new IllegalArgumentException("Only Bluetooth input ports may be masked/restored");
            check((Integer) audioSystem.getMethod("setDeviceConnectionState", attributes, int.class, int.class)
                    .invoke(null, device.payload, available ? 1 : 0, 0), available ? "restore Bluetooth input" : "mask Bluetooth input");
        }
        private static void check(int status, String operation) {
            if (status != 0) throw new IllegalStateException(operation + " returned " + status);
        }
    }

    private static final class DiskJournal implements RoutingController.Journal {
        final Platform api;
        DiskJournal(Platform api) { this.api = api; }
        @Override public void save(RoutingController.State state) throws Exception {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(buffer)) {
                out.writeInt(2);
                out.writeUTF(bootId());
                out.writeInt(state.originals.size());
                for (Map.Entry<Integer, List<RoutingController.Device>> entry : state.originals.entrySet()) {
                    out.writeInt(entry.getKey());
                    out.writeInt(entry.getValue().size());
                    for (RoutingController.Device device : entry.getValue()) writeDevice(out, device);
                }
                out.writeInt(state.applied.size());
                for (Map.Entry<Integer, RoutingController.Device> entry : state.applied.entrySet()) {
                    out.writeInt(entry.getKey()); writeDevice(out, entry.getValue());
                }
                out.writeInt(state.hidden.size());
                for (RoutingController.Device device : state.hidden.values()) writeDevice(out, device);
            }
            atomicWrite(JOURNAL, buffer.toByteArray());
        }
        RoutingController.State load() throws Exception {
            RoutingController.State state = new RoutingController.State();
            if (!JOURNAL.isFile()) return state;
            byte[] bytes = Files.readAllBytes(JOURNAL.toPath());
            if (bytes.length > 1024 * 1024) throw new IllegalStateException("Recovery journal too large");
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
                int schema = in.readInt();
                if (schema != 1 && schema != 2) throw new IllegalStateException("Unknown recovery journal schema");
                // Native routing is reset at boot. Never re-create ports from an older boot or OTA.
                if (schema == 2 && !in.readUTF().equals(bootId())) return state;
                int count = count(in);
                for (int i = 0; i < count; i++) {
                    int source = in.readInt();
                    List<RoutingController.Device> devices = new ArrayList<>();
                    int deviceCount = count(in);
                    for (int j = 0; j < deviceCount; j++) devices.add(readDevice(in));
                    state.originals.put(source, devices);
                }
                count = count(in);
                for (int i = 0; i < count; i++) { int source = in.readInt(); state.applied.put(source, readDevice(in)); }
                count = count(in);
                for (int i = 0; i < count; i++) { RoutingController.Device device = readDevice(in); state.hidden.put(device.key(), device); }
            }
            return state;
        }
        private static String bootId() throws Exception {
            return new String(Files.readAllBytes(new File("/proc/sys/kernel/random/boot_id").toPath()), StandardCharsets.UTF_8).trim();
        }
        private static int count(DataInputStream in) throws Exception {
            int count = in.readInt();
            if (count < 0 || count > 64) throw new IllegalStateException("Invalid recovery journal entry count");
            return count;
        }
        private static void writeDevice(DataOutputStream out, RoutingController.Device device) throws Exception {
            Parcel parcel = Parcel.obtain();
            try {
                parcel.writeParcelable((Parcelable) device.payload, 0);
                byte[] bytes = parcel.marshall();
                out.writeInt(bytes.length); out.write(bytes);
            } finally { parcel.recycle(); }
        }
        private RoutingController.Device readDevice(DataInputStream in) throws Exception {
            int length = in.readInt();
            if (length < 1 || length > 65536) throw new IllegalStateException("Invalid saved device size");
            byte[] bytes = new byte[length]; in.readFully(bytes);
            Parcel parcel = Parcel.obtain();
            try {
                parcel.unmarshall(bytes, 0, bytes.length); parcel.setDataPosition(0);
                return api.device(parcel.readParcelable(null));
            } finally { parcel.recycle(); }
        }
    }
}
