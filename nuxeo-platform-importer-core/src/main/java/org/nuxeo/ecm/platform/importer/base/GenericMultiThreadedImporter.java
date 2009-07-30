/*
 * (C) Copyright 2006-2008 Nuxeo SAS (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Nuxeo - initial API and implementation
 *
 * $Id$
 */

package org.nuxeo.ecm.platform.importer.base;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;

import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.PathRef;
import org.nuxeo.ecm.core.api.repository.Repository;
import org.nuxeo.ecm.core.api.repository.RepositoryManager;
import org.nuxeo.ecm.platform.audit.api.job.JobHistoryHelper;
import org.nuxeo.ecm.platform.importer.factories.DefaultDocumentModelFactory;
import org.nuxeo.ecm.platform.importer.factories.ImporterDocumentModelFactory;
import org.nuxeo.ecm.platform.importer.filter.ImporterFilter;
import org.nuxeo.ecm.platform.importer.log.ImporterLogger;
import org.nuxeo.ecm.platform.importer.log.PerfLogger;
import org.nuxeo.ecm.platform.importer.source.SourceNode;
import org.nuxeo.ecm.platform.importer.threading.DefaultMultiThreadingPolicy;
import org.nuxeo.ecm.platform.importer.threading.ImporterThreadingPolicy;
import org.nuxeo.runtime.api.Framework;

/**
 * 
 * Generic importer
 * 
 * @author Thierry Delprat
 * 
 */
public class GenericMultiThreadedImporter implements ImporterRunner {

    protected static ThreadPoolExecutor importTP;

    protected static Map<String, Long> nbCreatedDocsByThreads = new ConcurrentHashMap<String, Long>();

    protected ImporterThreadingPolicy threadPolicy;

    protected ImporterDocumentModelFactory factory;

    protected SourceNode importSource;

    protected DocumentModel targetContainer;

    protected Integer batchSize = 50;

    protected Integer nbThreads = 5;

    protected ImporterLogger log;

    protected CoreSession session;

    protected String importWritePath;

    protected String jobName;

    protected JobHistoryHelper jobHelper;

    protected boolean enablePerfLogging = true;

    protected List<ImporterFilter> filters = new ArrayList<ImporterFilter>();

    public static ThreadPoolExecutor getExecutor() {
        return importTP;
    }

    public static synchronized void addCreatedDoc(String taskId, long nbDocs) {
        String tid = Thread.currentThread().getName();
        nbCreatedDocsByThreads.put(tid + "-" + taskId, nbDocs);
    }

    public static synchronized long getCreatedDocsCounter() {
        long counter = 0;
        for (String tid : nbCreatedDocsByThreads.keySet()) {
            Long tCounter = nbCreatedDocsByThreads.get(tid);
            if (tCounter != null) {
                counter += tCounter;
            }
        }
        return counter;
    }

    public GenericMultiThreadedImporter(SourceNode sourceNode,
            String importWritePath, Integer batchSize, Integer nbThreads,
            ImporterLogger log) throws Exception {

        importSource = sourceNode;
        this.importWritePath = importWritePath;
        this.batchSize = batchSize;
        this.nbThreads = nbThreads;
        this.log = log;
    }

    public GenericMultiThreadedImporter(SourceNode sourceNode,
            String importWritePath, Integer batchSize, Integer nbThreads,
            String jobName, ImporterLogger log) throws Exception {

        this(sourceNode, importWritePath, batchSize, nbThreads, log);
        this.jobName = jobName;
        this.jobHelper = new JobHistoryHelper(jobName);
    }

    public void addFilter(ImporterFilter filter) {
        filters.add(filter);
    }

    protected CoreSession getCoreSession() throws Exception {
        if (this.session == null) {
            RepositoryManager rm = Framework.getService(RepositoryManager.class);
            Repository repo = rm.getDefaultRepository();
            session = repo.open();
        }
        return session;
    }

    public void run() {
        LoginContext lc = null;
        Exception finalException = null;
        try {
            lc = Framework.login();
            for (ImporterFilter filter : filters) {
                filter.handleBeforeImport();
            }
            doRun();
        } catch (Exception e) {
            log.error("Task exec failed", e);
            finalException = e;
        } finally {
            for (ImporterFilter filter : filters) {
                filter.handleAfterImport(finalException);
            }
            if (session != null) {
                CoreInstance.getInstance().close(session);
                session = null;
            }
            if (lc != null) {
                try {
                    lc.logout();
                } catch (LoginException e) {
                    log.error("Error during logout", e);
                }
            }
        }
    }

    protected GenericThreadedImportTask initRootTask(SourceNode importSource,
            DocumentModel targetContainer, ImporterLogger log,
            Integer batchSize, String jobName) throws Exception {
        GenericThreadedImportTask rootImportTask = new GenericThreadedImportTask(
                null, importSource, targetContainer, log, batchSize,
                getFactory(), getThreadPolicy(), jobName);
        return rootImportTask;
    }

    protected void doRun() throws Exception {

        targetContainer = getCoreSession().getDocument(
                new PathRef(importWritePath));

        nbCreatedDocsByThreads = new ConcurrentHashMap<String, Long>();

        importTP = new ThreadPoolExecutor(nbThreads, nbThreads, 500L,
                TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(100));

        GenericThreadedImportTask rootImportTask = initRootTask(importSource,
                targetContainer, log, batchSize, jobName);

        rootImportTask.setRootTask();
        long t0 = System.currentTimeMillis();
        if (jobHelper != null) {
            jobHelper.logJobStarted();
        }
        importTP.execute(rootImportTask);
        Thread.sleep(200);
        int activeTasks = importTP.getActiveCount();
        int oldActiveTasks = 0;
        long lastLogProgressTime = System.currentTimeMillis();
        long lastCreatedDocCounter = 0;

        String[] headers = { "nbDocs", "average", "imediate" };
        PerfLogger perfLogger = new PerfLogger(headers);
        while (activeTasks > 0) {
            Thread.sleep(500);
            activeTasks = importTP.getActiveCount();
            boolean logProgress = false;
            if (oldActiveTasks != activeTasks) {
                oldActiveTasks = activeTasks;
                log.debug("currently " + activeTasks + " active import Threads");
                logProgress = true;

            }
            long ti = System.currentTimeMillis();
            if (ti - lastLogProgressTime > 5000) {
                logProgress = true;
            }
            if (logProgress) {
                long inbCreatedDocs = getCreatedDocsCounter();
                long deltaT = ti - lastLogProgressTime;
                double averageSpeed = 1000 * ((float) (inbCreatedDocs) / (ti - t0));
                double imediateSpeed = averageSpeed;
                if (deltaT > 0) {
                    imediateSpeed = 1000 * ((float) (inbCreatedDocs - lastCreatedDocCounter) / (deltaT));
                }
                log.info(inbCreatedDocs + " docs created");
                log.info("average speed = " + averageSpeed + " docs/s");
                log.info("immediate speed = " + imediateSpeed + " docs/s");

                if (enablePerfLogging) {
                    Double[] perfData = { new Double(inbCreatedDocs),
                            averageSpeed, imediateSpeed };
                    perfLogger.log(perfData);
                }

                lastLogProgressTime = ti;
                lastCreatedDocCounter = inbCreatedDocs;
            }
        }
        log.info("All Threads terminated");
        perfLogger.release();
        if (jobHelper != null) {
            jobHelper.logJobEnded();
        }
        long t1 = System.currentTimeMillis();
        long nbCreatedDocs = getCreatedDocsCounter();
        log.info(nbCreatedDocs + " docs created");
        log.info(1000 * ((float) (nbCreatedDocs) / (t1 - t0)) + " docs/s");
        for (String k : nbCreatedDocsByThreads.keySet()) {
            log.info(k + " --> " + nbCreatedDocsByThreads.get(k));
        }
    }

    public ImporterThreadingPolicy getThreadPolicy() {
        if (threadPolicy == null) {
            threadPolicy = new DefaultMultiThreadingPolicy();
        }
        return threadPolicy;
    }

    public void setThreadPolicy(ImporterThreadingPolicy threadPolicy) {
        this.threadPolicy = threadPolicy;
    }

    public ImporterDocumentModelFactory getFactory() {
        if (factory == null) {
            factory = new DefaultDocumentModelFactory();
        }
        return factory;
    }

    public void setFactory(ImporterDocumentModelFactory factory) {
        this.factory = factory;
    }

    public void setEnablePerfLogging(boolean enablePerfLogging) {
        this.enablePerfLogging = enablePerfLogging;
    }

    public void stopImportProcrocess() {
        if (importTP != null && !importTP.isTerminated()
                && !importTP.isTerminating()) {
            importTP.shutdownNow();
        }
    }

}
