package com.clusin.objPool;

import com.lmax.disruptor.EventFactory;

/**
 * Created by dclusin on 4/1/14.
 */
public interface Poolable {

    void returnToPool();
}
