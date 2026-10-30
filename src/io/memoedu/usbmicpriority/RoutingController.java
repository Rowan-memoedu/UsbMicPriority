package io.memoedu.usbmicpriority;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Policy state machine, independent of Android so recovery paths can be tested on the host. */
public final class RoutingController {
    public static final int[] SOURCES = {1, 5, 6, 7, 9, 10};

    public static final class Device {
        public final boolean input;
        public final int type;
        public final String address;
        public final String name;
        public final Object payload;
        public Device(boolean input, int type, String address, String name, Object payload) {
            this.input = input;
            this.type = type;
            this.address = address == null ? "" : address;
            this.name = name == null ? "" : name;
            this.payload = payload;
        }
        public String key() { return (input ? "in:" : "out:") + type + ":" + address; }
        public boolean isUsbInput() { return input && (type == 11 || type == 12 || type == 22); }
        public boolean isBluetooth() { return type == 7 || type == 26; }
        @Override public boolean equals(Object other) {
            return other instanceof Device && key().equals(((Device) other).key());
        }
        @Override public int hashCode() { return key().hashCode(); }
    }

    public static final class State {
        public final Map<Integer, List<Device>> originals = new LinkedHashMap<>();
        public final Map<Integer, Device> applied = new LinkedHashMap<>();
        public final Map<String, Device> hidden = new LinkedHashMap<>();
        public boolean dirty() { return !originals.isEmpty() || !hidden.isEmpty(); }
    }

    public interface Platform {
        List<Device> devices() throws Exception;
        List<Device> preferred(int source) throws Exception;
        void prefer(int source, List<Device> devices) throws Exception;
        void connection(Device device, boolean available) throws Exception;
    }
    public interface Journal { void save(State state) throws Exception; }

    private final Platform platform;
    private final Journal journal;
    public final State state;
    private Device selected;

    public RoutingController(Platform platform, Journal journal, State state) {
        this.platform = platform;
        this.journal = journal;
        this.state = state;
    }

    /** Prefer USB for standard recording sources; mask only Bluetooth input ports, never outputs. */
    public String reconcile() throws Exception {
        List<Device> devices = platform.devices();
        Device usb = null;
        for (Device device : devices) {
            if (device.isUsbInput()) {
                if (usb == null) usb = device;
                if (device.equals(selected)) { usb = device; break; }
            }
        }
        if (usb == null) {
            restore();
            selected = null;
            return "WAITING_USB";
        }
        selected = usb;
        for (int source : SOURCES) {
            List<Device> current = platform.preferred(source);
            Device previous = state.applied.get(source);
            if (!state.originals.containsKey(source)) {
                state.originals.put(source, new ArrayList<>(current));
                journal.save(state);
            } else if (!current.isEmpty() && !current.equals(Arrays.asList(usb))
                    && !(previous != null && current.equals(Arrays.asList(previous)))
                    && !current.equals(state.originals.get(source))) {
                throw new IllegalStateException("External capture preference changed for source " + source);
            }
            // Record intent before the platform mutation, including when recovering from a crash.
            if (!usb.equals(previous)) {
                state.applied.put(source, usb);
                journal.save(state);
            }
            if (!current.equals(Arrays.asList(usb))) platform.prefer(source, Arrays.asList(usb));
        }
        for (Device device : devices) {
            if (device.input && device.isBluetooth()) {
                if (!state.hidden.containsKey(device.key())) {
                    state.hidden.put(device.key(), device);
                    journal.save(state);
                }
                platform.connection(device, false);
            }
        }
        // Drop recovery entries only when the peer really disconnected, not when we masked its mic.
        for (String key : new ArrayList<>(state.hidden.keySet())) {
            if (!hasPeerOutput(devices, state.hidden.get(key))) {
                state.hidden.remove(key);
                journal.save(state);
            }
        }
        verify();
        return "ACTIVE_USB";
    }

    private void verify() throws Exception {
        List<Device> devices = platform.devices();
        for (Device device : devices) {
            if (device.input && device.isBluetooth()) {
                throw new IllegalStateException("Bluetooth input remained available after masking");
            }
        }
        for (int source : SOURCES) {
            if (!platform.preferred(source).equals(Arrays.asList(selected))) {
                throw new IllegalStateException("USB preference readback failed for source " + source);
            }
        }
    }

    /** Reversible teardown, restoring inputs before clearing USB preferences. */
    public void restore() throws Exception {
        List<Device> devices = platform.devices();
        for (String key : new ArrayList<>(state.hidden.keySet())) {
            Device device = state.hidden.get(key);
            if (!devices.contains(device) && hasPeerOutput(devices, device)) {
                platform.connection(device, true);
            }
            state.hidden.remove(key);
            journal.save(state);
        }
        for (Integer source : new ArrayList<>(state.originals.keySet())) {
            List<Device> current = platform.preferred(source);
            List<Device> original = state.originals.get(source);
            Device applied = state.applied.get(source);
            // Preserve a new, unrelated preference written by another tool while this service ran.
            if ((applied != null && current.equals(Arrays.asList(applied))) || current.isEmpty()) {
                if (!current.equals(original)) platform.prefer(source, original);
            }
            state.applied.remove(source);
            state.originals.remove(source);
            journal.save(state);
        }
    }

    private static boolean hasPeerOutput(List<Device> devices, Device input) {
        for (Device device : devices) {
            if (!device.input && device.isBluetooth() && device.type == input.type
                    && device.address.equals(input.address)) return true;
        }
        return false;
    }

    public String selectedName() { return selected == null ? "" : selected.name; }
}
