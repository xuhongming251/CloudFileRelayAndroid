package com.cloudfilerelay.app;

import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class ModelCatalogConditionalUpdateTest {
    @Test
    public void secondRefreshUsesServerValidator() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ModelCatalogRepository repository = ModelCatalogRepository.get(target);

        CountDownLatch loadLatch = new CountDownLatch(1);
        repository.loadCached(result -> loadLatch.countDown());
        assertTrue(loadLatch.await(10, TimeUnit.SECONDS));

        CountDownLatch firstLatch = new CountDownLatch(1);
        AtomicReference<ModelCatalogRepository.Result> first = new AtomicReference<>();
        repository.refresh(true, result -> {
            first.set(result);
            firstLatch.countDown();
        });
        assertTrue(firstLatch.await(30, TimeUnit.SECONDS));
        assertTrue(first.get().error.isEmpty());

        CountDownLatch secondLatch = new CountDownLatch(1);
        AtomicReference<ModelCatalogRepository.Result> second = new AtomicReference<>();
        repository.refresh(true, result -> {
            second.set(result);
            secondLatch.countDown();
        });
        assertTrue(secondLatch.await(30, TimeUnit.SECONDS));
        assertTrue(second.get().error.isEmpty());
        assertTrue("连续检查未变化时应返回 304", second.get().notModified);
        assertTrue(second.get().items.size() > 7_000);
    }
}
