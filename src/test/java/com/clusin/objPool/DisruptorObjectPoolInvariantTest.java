package com.clusin.objPool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Base class for {@link pool.DisruptorObjectPool} invariant test cases.
 */
public abstract class DisruptorObjectPoolInvariantTest {
    public final static void runTest( final int numThreads, final int poolSize, final int runTimeInSeconds,
                                final TestObjectPool pool )
    {
        final Consumer[] consumers = new Consumer[numThreads];
        final Thread[] threads = new Thread[numThreads];

        System.out.println("Starting no garbage object pool test with numThreads=" + numThreads + " runTimeInSeconds=" + runTimeInSeconds + " poolSize=" + poolSize);

        for (int i = 0; i < numThreads; i++)
        {
            consumers[i] = new Consumer(poolSize, numThreads);
            threads[i] = new Thread(consumers[i]);
            threads[i].start();
        }

        long runCount = 0;
        final long startTimeMillis = System.currentTimeMillis();
        final Set<Long> producerConsumedObjects = new HashSet<Long>(poolSize);
        long producerDuplicateObjects = 0;
        do {
            final int index = (int)(runCount % numThreads);
            TestObject borrowedObject = null;

            try {
                borrowedObject = pool.borrowObject();
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

            if (borrowedObject != null)
            {
                final boolean added = producerConsumedObjects.add(borrowedObject.objectIndex);
                if (!added)
                {
                    producerDuplicateObjects++;
                }

                try {
                    consumers[index].theQ.put(borrowedObject);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }

            runCount++;
        } while (((System.currentTimeMillis() - startTimeMillis) / 1000L) < runTimeInSeconds);

        for (Consumer c : consumers)
        {
            c.stopped = true;
        }

        for (Thread t : threads)
        {
            try {
                t.join();
            }
            catch (InterruptedException e)
            {
                e.printStackTrace();
            }
        }

        System.out.println("Object pool testing completed. Printing statistics.");

        System.out.println("Producer Thread:");
        System.out.println("Objects created: " + TestObject.indexCount);
        System.out.println("Number of objects created after initialization: " + pool.getObjectsCreated());
        System.out.println("Unique pooled objects used: " + producerConsumedObjects.size());
        System.out.println("Duplicate pooled objects used: " + producerDuplicateObjects);
        System.out.println("Number of times producer thread had to wait for objects: " + pool.getBorrowLockCount() + "\n");

        for (Consumer c : consumers)
        {
            System.out.println("Consumer Thread " + c.threadIndex + ":");
            System.out.println("Unique pooled objects used: " + c.processedObjectIndexes.size());
            System.out.println("Duplicate pooled objects used: " + c.duplicateProcessedObjects);
        }
    }

    private static final class Consumer implements Runnable
    {
        final ArrayBlockingQueue<TestObject> theQ;

        volatile boolean stopped = false;

        final Set<Long> processedObjectIndexes;

        int duplicateProcessedObjects;

        static int threadCounter = 0;

        final int threadIndex;

        final ArrayList<TestObject> localQ;

        public Consumer(final int poolSize, final int numThreads)
        {
            theQ = new ArrayBlockingQueue<TestObject>(poolSize / numThreads);
            localQ = new ArrayList<TestObject>(poolSize / numThreads);

            processedObjectIndexes = new HashSet<Long>(poolSize);
            threadIndex = threadCounter++;
            duplicateProcessedObjects = 0;
        }

        @Override
        public void run() {
            Thread.currentThread().setName("ConsumerThread-" + threadIndex);

            System.out.println(Thread.currentThread().getName() + " started");

            do
            {
                try {
                    if (theQ.size() > 0) {
                        theQ.drainTo(localQ);

                        for (TestObject receivedObject : localQ) {
                            boolean added = processedObjectIndexes.add(receivedObject.objectIndex);

                            if (!added) {
                                duplicateProcessedObjects++;
                            }

                            receivedObject.returnToPool();
                        }

                        localQ.clear();
                    }
                    else
                    {
                        Thread.sleep(10);
                    }
                }
                catch (InterruptedException e)
                {
                    System.out.println(Thread.currentThread().getName() + " interrupted");
                }
            } while (!stopped || !theQ.isEmpty());

            System.out.println(Thread.currentThread().getName() + " completed");
        }
    }

    protected static final class TestObjectPool extends DisruptorObjectPool<TestObject>
    {
        public TestObjectPool(int poolSize, boolean createGarbage)
        {
            super(poolSize, createGarbage);
        }

        @Override
        public TestObject createNewObject(DisruptorObjectPool<TestObject> owningPool) {
            return new TestObject(owningPool);
        }
    }

    protected static final class TestObject implements Poolable
    {
        private final DisruptorObjectPool<TestObject> owningPool;

        public final Long objectIndex;

        public static long indexCount = 0;

        public TestObject(DisruptorObjectPool<TestObject> owningPool)
        {
            this.owningPool = owningPool;
            objectIndex = ++indexCount;
        }

        @Override
        public void returnToPool()
        {
            owningPool.returnObject(this);
        }
    }
}
