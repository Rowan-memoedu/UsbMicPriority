package io.memoedu.usbmicpriority;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static io.memoedu.usbmicpriority.RoutingController.*;

/** Regression checks for device identity independence, journaling, and reversible global effects. */
public final class RoutingControllerTest {
    static final Device USB = new Device(true, 11, "card=2;device=4", "USB A", null);
    static final Device USB2 = new Device(true, 22, "card=9;device=1", "Unrelated USB headset", null);
    static final Device BT_IN = new Device(true, 7, "test-peer", "BT mic", null);
    static final Device BT_OUT = new Device(false, 7, "test-peer", "BT calls", null);
    static final Device MEDIA = new Device(false, 8, "test-peer", "BT media", null);
    static final Device BUILTIN = new Device(true, 15, "bottom", "Built in", null);

    static final class MemoryJournal implements Journal {
        State saved = new State();
        public void save(State state) {
            State copy = new State();
            for (Map.Entry<Integer,List<Device>> e : state.originals.entrySet()) copy.originals.put(e.getKey(), new ArrayList<>(e.getValue()));
            copy.applied.putAll(state.applied); copy.hidden.putAll(state.hidden); saved = copy;
        }
    }
    static final class FakePlatform implements Platform {
        final List<Device> connected = new ArrayList<>();
        final Map<Integer,List<Device>> preferences = new LinkedHashMap<>();
        final MemoryJournal journal;
        int failSource = -1;
        int writes;
        FakePlatform(MemoryJournal journal, Device... devices) { this.journal = journal; connected.addAll(Arrays.asList(devices)); }
        public List<Device> devices() { return new ArrayList<>(connected); }
        public List<Device> preferred(int source) { return new ArrayList<>(preferences.getOrDefault(source, Collections.emptyList())); }
        public void prefer(int source, List<Device> devices) {
            require(journal.saved.originals.containsKey(source), "Preference mutation must have a recovery checkpoint");
            if (source == failSource) { failSource = -1; throw new IllegalStateException("injected platform failure"); }
            preferences.put(source, new ArrayList<>(devices)); writes++;
        }
        public void connection(Device device, boolean available) {
            require(device.input, "Output ports must never be disconnected");
            require(journal.saved.hidden.containsKey(device.key()), "Input mutation must have a recovery checkpoint");
            if (available) connected.add(device); else connected.remove(device);
            writes++;
        }
    }
    static RoutingController create(FakePlatform p, MemoryJournal j) { return new RoutingController(p, j, new State()); }
    static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        int passed = 0;
        {
            MemoryJournal j = new MemoryJournal(); FakePlatform p = new FakePlatform(j, BT_IN, BT_OUT, MEDIA);
            require(create(p,j).reconcile().equals("WAITING_USB") && p.writes == 0, "No USB must leave routing untouched"); passed++;
        }
        {
            MemoryJournal j = new MemoryJournal(); FakePlatform p = new FakePlatform(j, USB, BT_IN, BT_OUT, MEDIA, BUILTIN);
            RoutingController c = create(p,j); c.reconcile();
            require(!p.connected.contains(BT_IN) && p.connected.contains(BT_OUT) && p.connected.contains(MEDIA), "Only BT microphone may be masked");
            for (int s : SOURCES) require(p.preferred(s).equals(Arrays.asList(USB)), "Every supported recording source prefers USB");
            int writes = p.writes; c.reconcile(); require(p.writes == writes, "Steady state must not rewrite platform settings");
            p.connected.remove(USB); c.reconcile();
            require(p.connected.contains(BT_IN) && !c.state.dirty(), "Unplug must restore BT input and empty recovery journal");
            for (int s : SOURCES) require(p.preferred(s).isEmpty(), "Original empty preferences must be restored"); passed++;
        }
        {
            MemoryJournal j = new MemoryJournal(); FakePlatform p = new FakePlatform(j, USB2, BT_IN, BT_OUT);
            RoutingController c = create(p,j); c.reconcile();
            require(p.preferred(7).equals(Arrays.asList(USB2)), "Independent USB headset identity must work without fixture-specific rules");
            c.restore(); passed++;
        }
        {
            MemoryJournal j = new MemoryJournal(); FakePlatform p = new FakePlatform(j, USB, BT_IN, BT_OUT);
            RoutingController c = create(p,j); c.reconcile(); p.connected.remove(BT_OUT); p.connected.remove(USB); c.reconcile();
            require(!p.connected.contains(BT_IN), "Do not create a ghost input for a disconnected headset"); passed++;
        }
        {
            MemoryJournal j = new MemoryJournal(); FakePlatform p = new FakePlatform(j, USB, BT_IN, BT_OUT);
            RoutingController c = create(p,j); c.reconcile();
            RoutingController recovered = new RoutingController(p,j,j.saved); recovered.restore();
            require(p.connected.contains(BT_IN) && !recovered.state.dirty(), "Recovery must restore a masked input after a simulated crash"); passed++;
        }
        {
            MemoryJournal j = new MemoryJournal(); FakePlatform p = new FakePlatform(j, USB, BT_IN, BT_OUT);
            RoutingController c = create(p,j); p.failSource = 5;
            boolean failed = false; try { c.reconcile(); } catch (IllegalStateException expected) { failed = true; }
            require(failed, "Injected failure must be surfaced"); c.restore();
            require(!c.state.dirty() && p.preferred(1).isEmpty(), "Partial application must roll back"); passed++;
        }
        {
            MemoryJournal j = new MemoryJournal(); FakePlatform p = new FakePlatform(j, USB, BT_IN, BT_OUT);
            p.preferences.put(6, Arrays.asList(BUILTIN));
            RoutingController c = create(p,j); c.reconcile(); c.restore();
            require(p.preferred(6).equals(Arrays.asList(BUILTIN)), "Pre-existing preference must be restored"); passed++;
        }
        {
            MemoryJournal j = new MemoryJournal(); FakePlatform p = new FakePlatform(j, USB, BT_IN, BT_OUT);
            RoutingController c = create(p,j); c.reconcile(); p.preferences.put(6, Arrays.asList(BUILTIN)); c.restore();
            require(p.preferred(6).equals(Arrays.asList(BUILTIN)), "Do not overwrite an unrelated later preference"); passed++;
        }
        System.out.println("PASS " + passed + " routing/recovery checks");
    }
}
