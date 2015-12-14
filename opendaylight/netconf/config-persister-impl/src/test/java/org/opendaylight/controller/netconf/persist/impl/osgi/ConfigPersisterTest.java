/*
 * Copyright (c) 2013 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.controller.netconf.persist.impl.osgi;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import com.google.common.collect.Sets;
import java.io.IOException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.opendaylight.controller.config.api.ConflictingVersionException;
import org.opendaylight.controller.netconf.api.Capability;
import org.opendaylight.controller.netconf.api.NetconfDocumentedException;
import org.opendaylight.controller.netconf.mapping.api.HandlingPriority;
import org.opendaylight.controller.netconf.mapping.api.NetconfOperation;
import org.opendaylight.controller.netconf.mapping.api.NetconfOperationChainedExecution;
import org.opendaylight.controller.netconf.mapping.api.NetconfOperationService;
import org.opendaylight.controller.netconf.mapping.api.NetconfOperationServiceFactory;
import org.opendaylight.controller.netconf.persist.impl.ConfigPusherImpl;
import org.opendaylight.controller.netconf.persist.impl.osgi.MockedBundleContext.DummyAdapterWithInitialSnapshot;
import org.opendaylight.controller.netconf.util.xml.XmlUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

public class ConfigPersisterTest {
    private static final Logger LOG = LoggerFactory.getLogger(ConfigPersisterTest.class);

    private MockedBundleContext ctx;
    private ConfigPersisterActivator configPersisterActivator;
    private TestingExceptionHandler handler;

    private void setUpContext(String requiredCapability) throws Exception {
        DummyAdapterWithInitialSnapshot.expectedCapability = requiredCapability;
        ctx = new MockedBundleContext(1000, 1000);
        configPersisterActivator = new ConfigPersisterActivator() {
            @Override
            protected ConfigPusherImpl newConfigPusher(NetconfOperationServiceFactory service,
                    long maxWaitForCapabilitiesMillis, long conflictingVersionTimeoutMillis) {
                return new ConfigPusherImpl(service, maxWaitForCapabilitiesMillis, conflictingVersionTimeoutMillis) {
                    @Override
                    protected void onFailedConfigPush(String message, Exception cause) {
                        handler.uncaughtException(Thread.currentThread(), cause);
                        super.onFailedConfigPush(message, cause);
                    }

                };
            }
        };
    }

    private void setUpContextAndStartPersister(String requiredCapability, final NetconfOperationService conflictingService) throws Exception {
        setUpContext(requiredCapability);
        doReturn(conflictingService).when(ctx.serviceFactory).createService(anyString());
        configPersisterActivator.start(ctx.getBundleContext());
    }

    @Before
    public void setUp() {
        handler = new TestingExceptionHandler();
    }

    @After
    public void tearDown() throws Exception {
        configPersisterActivator.stop(ctx.getBundleContext());
    }

    @Test
    public void testPersisterNotAllCapabilitiesProvided() throws Exception {
        setUpContextAndStartPersister("required-cap", getConflictingService());
        Thread.sleep(2000);
        handler.assertException(IllegalStateException.class, "Required yang models that are missing: [required-cap]");

    }

    @Test
    public void testPersisterSuccessfulPush() throws Exception {
        setUpContextAndStartPersister("cap1", getWorkingService(getOKDocument()));
        Thread.sleep(2000);
        handler.assertNoException();
    }

    // this means pushing of config was successful
    public void assertCannotRegisterAsJMXListener_pushWasSuccessful() {
        handler.assertException(IllegalStateException.class, "Cannot register as JMX listener to netconf");
    }

    public NetconfOperationService getWorkingService(Document document) throws SAXException, IOException, NetconfDocumentedException {
        NetconfOperationService service = mock(NetconfOperationService.class);
        Capability capability = mock(Capability.class);
//        doReturn(Sets.newHashSet(capability)).when(service).getCapabilities();
        doReturn("cap1").when(capability).getCapabilityUri();


        NetconfOperation mockedOperation = mock(NetconfOperation.class);
        doReturn(Sets.newHashSet(mockedOperation)).when(service).getNetconfOperations();
        doReturn(HandlingPriority.getHandlingPriority(1)).when(mockedOperation).canHandle(any(Document.class));
        doReturn(document).when(mockedOperation).handle(any(Document.class), any(NetconfOperationChainedExecution.class));
        doNothing().when(service).close();
        return service;
    }

    private Document getOKDocument() throws SAXException, IOException {
        return XmlUtil.readXmlToDocument(
                "<rpc-reply message-id=\"1\" xmlns=\"urn:ietf:params:xml:ns:netconf:base:1.0\">\n" +
                        "<ok/>\n" +
                        "</rpc-reply>"
        );
    }


    @Test
    public void testPersisterConflictingVersionException() throws Exception {
        setUpContextAndStartPersister("cap1", getConflictingService());

        Thread.sleep(2000);
        handler.assertException(IllegalStateException.class, "Max wait for conflicting version stabilization timeout");
    }

    private NetconfOperationService getConflictingService() throws Exception {
        NetconfOperationService service =  getWorkingService(getOKDocument());
        ConflictingVersionException cve = new ConflictingVersionException("");
        try {
            NetconfDocumentedException.wrap(cve);
            throw new AssertionError("Should throw an exception");
        }catch(NetconfDocumentedException e) {
            NetconfOperation mockedOperation = service.getNetconfOperations().iterator().next();
            doThrow(e).when(mockedOperation).handle(any(Document.class), any(NetconfOperationChainedExecution.class));
            return service;
        }
    }

    @Test
    public void testSuccessConflictingVersionException() throws Exception {
        LOG.info("testSuccessConflictingVersionException starting");

        setUpContext("cap1");

        NetconfOperationService conflictingService = getConflictingService();
        NetconfOperationService workingService = getWorkingService(getOKDocument());

        doReturn(conflictingService).doReturn(conflictingService).doReturn(conflictingService).
            doReturn(workingService).when(ctx.serviceFactory).createService(anyString());

        configPersisterActivator.start(ctx.getBundleContext());
        Thread.sleep(2000);
        handler.assertNoException();
    }

}