package com.clusin.objPool;

/**
 * Invariant test that assumes producers & consumers are well behaved.
 * - Total objects created = pool size.
 * - Set of objects ID's retrieved from object pool = total objects created
 * - Each consumer thread's set of object ID's retrieved = total objects created
 * - # of times producer thread had to wait for objects returned to pool > 0
 */
public class DisruptorObjectPoolInvariantTestNoGarbage extends DisruptorObjectPoolInvariantTest
{
    public static void main(String[] args)
    {
        final int numThreads = 2 ;
        final int runTimeInSeconds = 60;

        final int poolSize = 4096;
        final TestObjectPool pool = new TestObjectPool(poolSize, false);

        runTest(numThreads, poolSize, runTimeInSeconds, pool);
    }
}
