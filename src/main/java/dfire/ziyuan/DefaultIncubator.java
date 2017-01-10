/*
 * Copyright (C) 2009-2016 Hangzhou 2Dfire Technology Co., Ltd. All rights reserved
 */
package dfire.ziyuan;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.pool.KryoFactory;
import dfire.ziyuan.exceptions.DefaultFKCExceptionHandler;
import dfire.ziyuan.exceptions.FKCExceptionHandler;
import dfire.ziyuan.pool.DefaultIOPoolConfig;
import dfire.ziyuan.pool.KryoPool;
import dfire.ziyuan.poolobjfactory.StreamHolderFactory;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.concurrent.locks.ReentrantLock;

/**
 * DefaultIncubator 默认的incubator,使用spi机制创建
 *
 * @author ziyuan
 * @since 2017-01-06
 */
class DefaultIncubator<T> implements Incubator<T> {

    /**
     * 是否是关闭了的
     */
    private boolean isClosed = false;

    private final ReentrantLock lock = new ReentrantLock(false);

    private FKCExceptionHandler exceptionHandler;

    private StreamHolderFactory streamHolderFactory;

    private GenericObjectPool<StreamHolder> holderPool;

    private KryoPool kryoPool;

    public DefaultIncubator() {
        init();
    }

    /**
     * 进行初始化操作
     */
    private void init() {
        GenericObjectPoolConfig defaultcfg = DefaultIOPoolConfig.getConfig();
        if (exceptionHandler == null) {
            exceptionHandler = new DefaultFKCExceptionHandler();
        }
        streamHolderFactory = new StreamHolderFactory(exceptionHandler);
        holderPool = new GenericObjectPool<StreamHolder>(streamHolderFactory, defaultcfg);

        //初始化相关池对象
        kryoPool = new KryoPool.Builder(new KryoFactory() {
            @Override
            public Kryo create() {
                return new Kryo();
            }
        }).isQueueSoftRef(true).build();

        //初始化的时候先设置好钩子,防止使用的时候忘记关闭
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                doShutDown();
            }
        }));
    }

    @Override
    public T born(T template) {
        Kryo kryo = kryoPool.borrowOne();
        try {
            StreamHolder holder = holderPool.borrowObject();
            ObjectOutputStream oos = holder.getOos();
            Output output = new Output(oos);
            kryo.writeObject(output, template);

            ObjectInputStream ois = holder.getOis();
            Input input = new Input(ois);
            return (T) kryo.readObject(input, template.getClass());
        } catch (Exception e) {
            exceptionHandler.dealException(e);
        }
        return null;
    }

    @Override
    public void shutdown() {
        doShutDown();
    }

    @Override
    public void setExceptionHandler(FKCExceptionHandler exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
    }

    /**
     * 执行停止操作
     */
    private void doShutDown() {
        lock.lock();
        try {
            if (isClosed) {
                return;
            }
            if (this.kryoPool != null) {
                kryoPool.close();
            }
            isClosed = true;
        } finally {
            lock.unlock();
        }
    }
}
