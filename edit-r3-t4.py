p = r'C:\Users\--\AppData\Local\Temp\claude\C--Users---\bc8d0470-d721-43db-bccb-778058d1f739\scratchpad\lane-b\extensions\instagram\src\test\java\app\hushgram\extension\instagram\feed\HiddenAccountsTest.java'
s = open(p, encoding='utf-8', newline='').read()
nl = '\r\n' if '\r\n' in s else '\n'
s = s.replace('\r\n', '\n')
anchor = '''    /** Paused, every post shows, and the list keeps what you chose. */'''
assert anchor in s
new = '''    /** Edits from two threads at once (the settings screen and a rebuild of it) each keep their name. */
    @Test
    public void concurrentEditsAreAllKept() throws Exception {
        signIn("1");
        int threads = 8, each = 25;
        java.util.concurrent.CyclicBarrier together = new java.util.concurrent.CyclicBarrier(threads);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            java.util.List<java.util.concurrent.Future<?>> done = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int worker = t;
                done.add(pool.submit(() -> {
                    together.await();
                    for (int i = 0; i < each; i++) HiddenAccounts.add("user" + worker + "_" + i);
                    return null;
                }));
            }
            for (java.util.concurrent.Future<?> f : done) f.get(60, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(threads * each, HiddenAccounts.saved().size());

        java.util.concurrent.CyclicBarrier again = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.ExecutorService two = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<?> adding = two.submit(() -> {
                again.await();
                for (int i = 0; i < each; i++) HiddenAccounts.add("late" + i);
                return null;
            });
            java.util.concurrent.Future<?> removing = two.submit(() -> {
                again.await();
                for (int i = 0; i < each; i++) HiddenAccounts.remove("user0_" + i);
                return null;
            });
            adding.get(60, java.util.concurrent.TimeUnit.SECONDS);
            removing.get(60, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            two.shutdownNow();
        }
        assertEquals(threads * each, HiddenAccounts.saved().size());
        assertTrue(HiddenAccounts.saved().contains("late" + (each - 1)));
        assertFalse(HiddenAccounts.saved().contains("user0_0"));
    }

'''
s = s.replace(anchor, new + anchor, 1)
open(p, 'w', encoding='utf-8', newline='').write(s.replace('\n', nl))
