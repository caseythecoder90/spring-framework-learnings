package com.caseythecoder.spring.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe log of "something happened", used by the labs to make Spring's behaviour observable.
 *
 * <p>Every lab assertion in this repo comes down to one of three questions, and this class answers
 * all three: <em>what ran</em> ({@link #labels()}), <em>on which thread</em> ({@link #threadsFor}),
 * and <em>when relative to everything else</em> ({@link #gapsFor}).
 */
public final class Recorder {

    /**
     * @param label      caller-supplied name of the thing that happened
     * @param thread     name of the thread that recorded it
     * @param offsetNanos nanoseconds since this recorder was created
     */
    public record Entry(String label, String thread, long offsetNanos) {

        public Duration offset() {
            return Duration.ofNanos(offsetNanos);
        }
    }

    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    private final long originNanos = System.nanoTime();

    /** Records that {@code label} happened on the current thread, now. */
    public void record(String label) {
        entries.add(new Entry(label, Thread.currentThread().getName(), System.nanoTime() - originNanos));
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** Labels in the order they were recorded — the usual target for an ordering assertion. */
    public List<String> labels() {
        return entries.stream().map(Entry::label).toList();
    }

    public long countOf(String label) {
        return entries.stream().filter(e -> e.label().equals(label)).count();
    }

    /** Distinct thread names that recorded {@code label}, in first-seen order. */
    public Set<String> threadsFor(String label) {
        Set<String> threads = new LinkedHashSet<>();
        entries.stream().filter(e -> e.label().equals(label)).forEach(e -> threads.add(e.thread()));
        return threads;
    }

    /** Distinct thread names across every entry, in first-seen order. */
    public Set<String> allThreads() {
        Set<String> threads = new LinkedHashSet<>();
        entries.forEach(e -> threads.add(e.thread()));
        return threads;
    }

    /**
     * Gaps between consecutive occurrences of {@code label}. For a task recorded once per run this
     * is the observed period, which is what separates {@code fixedRate} from {@code fixedDelay}.
     */
    public List<Duration> gapsFor(String label) {
        List<Entry> matching = entries.stream().filter(e -> e.label().equals(label)).toList();
        List<Duration> gaps = new ArrayList<>();
        for (int i = 1; i < matching.size(); i++) {
            gaps.add(Duration.ofNanos(matching.get(i).offsetNanos() - matching.get(i - 1).offsetNanos()));
        }
        return gaps;
    }

    /** True if {@code first} was recorded before {@code second} ever was. */
    public boolean recordedBefore(String first, String second) {
        int firstIndex = labels().indexOf(first);
        int secondIndex = labels().indexOf(second);
        return firstIndex >= 0 && (secondIndex < 0 || firstIndex < secondIndex);
    }

    public void clear() {
        entries.clear();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Recorder[\n");
        entries.forEach(e -> sb.append("  %7d ms  %-28s %s%n".formatted(
                e.offset().toMillis(), e.thread(), e.label())));
        return sb.append(']').toString();
    }
}
