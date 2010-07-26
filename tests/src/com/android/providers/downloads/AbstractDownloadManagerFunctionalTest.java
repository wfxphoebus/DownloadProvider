/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.downloads;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Downloads;
import android.test.MoreAsserts;
import android.test.RenamingDelegatingContext;
import android.test.ServiceTestCase;
import android.test.mock.MockContentResolver;
import android.util.Log;
import tests.http.MockResponse;
import tests.http.MockWebServer;
import tests.http.RecordedRequest;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public abstract class AbstractDownloadManagerFunctionalTest extends
        ServiceTestCase<DownloadService> {

    protected static final String LOG_TAG = "DownloadManagerFunctionalTest";
    private static final String PROVIDER_AUTHORITY = "downloads";
    protected static final long REQUEST_TIMEOUT_MILLIS = 10 * 1000;
    protected static final long RETRY_DELAY_MILLIS = 61 * 1000;
    protected static final String FILE_CONTENT = "hello world";
    protected static final int HTTP_OK = 200;
    protected static final int HTTP_PARTIAL_CONTENT = 206;
    protected static final int HTTP_NOT_FOUND = 404;
    protected static final int HTTP_SERVICE_UNAVAILABLE = 503;
    protected MockWebServer mServer;
    protected MockContentResolverWithNotify mResolver;
    protected TestContext mTestContext;
    protected FakeSystemFacade mSystemFacade;

    static interface StatusReader {
        public int getStatus();
        public boolean isComplete(int status);
    }

    static class MockContentResolverWithNotify extends MockContentResolver {
        public boolean mNotifyWasCalled = false;

        public synchronized void resetNotified() {
            mNotifyWasCalled = false;
        }

        @Override
        public synchronized void notifyChange(Uri uri, ContentObserver observer,
                boolean syncToNetwork) {
            mNotifyWasCalled = true;
            notifyAll();
        }
    }

    /**
     * Context passed to the provider and the service.  Allows most methods to pass through to the
     * real Context (this is a LargeTest), with a few exceptions, including renaming file operations
     * to avoid file and DB conflicts (via RenamingDelegatingContext).
     */
    static class TestContext extends RenamingDelegatingContext {
        private static final String FILENAME_PREFIX = "test.";

        private Context mRealContext;
        private Set<String> mAllowedSystemServices;
        private ContentResolver mResolver;

        boolean mHasServiceBeenStarted = false;

        public TestContext(Context realContext) {
            super(realContext, FILENAME_PREFIX);
            mRealContext = realContext;
            mAllowedSystemServices = new HashSet<String>(Arrays.asList(new String[] {
                    Context.NOTIFICATION_SERVICE,
                    Context.POWER_SERVICE,
            }));
        }

        public void setResolver(ContentResolver resolver) {
            mResolver = resolver;
        }

        /**
         * Direct DownloadService to our test instance of DownloadProvider.
         */
        @Override
        public ContentResolver getContentResolver() {
            assert mResolver != null;
            return mResolver;
        }

        /**
         * Stub some system services, allow access to others, and block the rest.
         */
        @Override
        public Object getSystemService(String name) {
            if (mAllowedSystemServices.contains(name)) {
                return mRealContext.getSystemService(name);
            }
            return super.getSystemService(name);
        }

        /**
         * Record when DownloadProvider starts DownloadService.
         */
        @Override
        public ComponentName startService(Intent service) {
            if (service.getComponent().getClassName().equals(DownloadService.class.getName())) {
                mHasServiceBeenStarted = true;
                return service.getComponent();
            }
            throw new UnsupportedOperationException("Unexpected service: " + service);
        }
    }

    public AbstractDownloadManagerFunctionalTest() {
        super(DownloadService.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mSystemFacade = new FakeSystemFacade();
        Context realContext = getContext();
        mTestContext = new TestContext(realContext);
        setupProviderAndResolver();
        assert isDatabaseEmpty(); // ensure we're not messing with real data

        mTestContext.setResolver(mResolver);
        setContext(mTestContext);
        setupService();
        getService().mSystemFacade = mSystemFacade;

        mServer = new MockWebServer();
        mServer.play();
    }

    @Override
    protected void tearDown() throws Exception {
        waitForThreads();
        cleanUpDownloads();
        super.tearDown();
    }

    private void waitForThreads() throws InterruptedException {
        DownloadService service = getService();
        if (service == null) {
            return;
        }

        long startTimeMillis = System.currentTimeMillis();
        while (service.mUpdateThread != null
                && System.currentTimeMillis() < startTimeMillis + 1000) {
            Thread.sleep(50);
        }

        // We can't explicitly wait for DownloadThreads, so just throw this last sleep in.  Ugly,
        // but necessary to avoid unbearable flakiness until I can find a better solution.
        Thread.sleep(50);
    }

    private boolean isDatabaseEmpty() {
        Cursor cursor = mResolver.query(Downloads.CONTENT_URI, null, null, null, null);
        try {
            return cursor.getCount() == 0;
        } finally {
            cursor.close();
        }
    }

    void setupProviderAndResolver() {
        DownloadProvider provider = new DownloadProvider();
        provider.mSystemFacade = mSystemFacade;
        provider.attachInfo(mTestContext, null);
        mResolver = new MockContentResolverWithNotify();
        mResolver.addProvider(PROVIDER_AUTHORITY, provider);
    }

    /**
     * Remove any downloaded files and delete any lingering downloads.
     */
    void cleanUpDownloads() {
        if (mResolver == null) {
            return;
        }
        String[] columns = new String[] {Downloads._DATA};
        Cursor cursor = mResolver.query(Downloads.CONTENT_URI, columns, null, null, null);
        try {
            for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
                String filePath = cursor.getString(0);
                if (filePath == null) continue;
                Log.d(LOG_TAG, "Deleting " + filePath);
                new File(filePath).delete();
            }
        } finally {
            cursor.close();
        }
        mResolver.delete(Downloads.CONTENT_URI, null, null);
    }

    /**
     * Enqueue a response from the MockWebServer.
     */
    MockResponse enqueueResponse(int status, String body) {
        return enqueueResponse(status, body, true);
    }

    MockResponse enqueueResponse(int status, String body, boolean includeContentType) {
        MockResponse response = new MockResponse()
                                .setResponseCode(status)
                                .setBody(body);
        if (includeContentType) {
            response.addHeader("Content-type", "text/plain");
        }
        mServer.enqueue(response);
        return response;
    }

    MockResponse enqueueEmptyResponse(int status) {
        return enqueueResponse(status, "");
    }

    /**
     * Wait for a request to come to the MockWebServer and return it.
     */
    RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest request = mServer.takeRequestWithTimeout(REQUEST_TIMEOUT_MILLIS);
        assertNotNull("Timed out waiting for request", request);
        return request;
    }

    String getServerUri(String path) throws MalformedURLException {
        return mServer.getUrl(path).toString();
    }

    /**
     * Run the service and wait for a request and for the download to reach the given status.
     * @return the request received
     */
    protected RecordedRequest runUntilStatus(StatusReader reader, int status) throws Exception {
        startService(null);
        RecordedRequest request = takeRequest();
        waitForDownloadToStop(reader, status);
        return request;
    }

    /**
     * Wait for a download to given a given status, with a timeout.  Fails if the download reaches
     * any other final status.
     */
    protected void waitForDownloadToStop(StatusReader reader, int expectedStatus)
            throws Exception {
        long startTimeMillis = System.currentTimeMillis();
        long endTimeMillis = startTimeMillis + REQUEST_TIMEOUT_MILLIS;
        int status = reader.getStatus();
        while (status != expectedStatus) {
            if (reader.isComplete(status)) {
                fail("Download completed with unexpected status: " + status);
            }
            waitForChange(endTimeMillis);
            if (startTimeMillis > endTimeMillis) {
                fail("Download timed out with status " + status);
            }
            mServer.checkForExceptions();
            status = reader.getStatus();
        }

        long delta = System.currentTimeMillis() - startTimeMillis;
        Log.d(LOG_TAG, "Status " + status + " reached after " + delta + "ms");
    }

    /**
     * Wait until mResolver gets notifyChange() called, or endTimeMillis is reached.
     */
    private void waitForChange(long endTimeMillis) {
        synchronized(mResolver) {
            long now = System.currentTimeMillis();
            while (!mResolver.mNotifyWasCalled && now < endTimeMillis) {
                try {
                    mResolver.wait(endTimeMillis - now);
                } catch (InterruptedException exc) {
                    // no problem
                }
                now = System.currentTimeMillis();
            }
            mResolver.resetNotified();
        }
    }

    protected String readStream(InputStream inputStream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        try {
            char[] buffer = new char[1024];
            int length = reader.read(buffer);
            assertTrue("Failed to read anything from input stream", length > -1);
            return String.valueOf(buffer, 0, length);
        } finally {
            reader.close();
        }
    }

    protected void assertStartsWith(String expectedPrefix, String actual) {
        String regex = "^" + expectedPrefix + ".*";
        MoreAsserts.assertMatchesRegex(regex, actual);
    }
}
