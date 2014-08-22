/*******************************************************************************
 *     Cloud Foundry 
 *     Copyright (c) [2009-2014] Pivotal Software, Inc. All Rights Reserved.
 *
 *     This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *     You may not use this product except in compliance with the License.
 *
 *     This product includes a number of subcomponents with
 *     separate copyright notices and license terms. Your use of these
 *     subcomponents is subject to the terms and conditions of the
 *     subcomponent's license, as noted in the LICENSE file.
 *******************************************************************************/
package org.cloudfoundry.identity.uaa.oauth;

import org.cloudfoundry.identity.uaa.oauth.approval.Approval;
import org.cloudfoundry.identity.uaa.oauth.approval.ApprovalStore;
import org.cloudfoundry.identity.uaa.oauth.approval.JdbcApprovalStore;
import org.cloudfoundry.identity.uaa.rest.QueryableResourceManager;
import org.cloudfoundry.identity.uaa.rest.jdbc.JdbcPagingListFactory;
import org.cloudfoundry.identity.uaa.rest.jdbc.LimitSqlAdapter;
import org.cloudfoundry.identity.uaa.rest.jdbc.SimpleSearchQueryConverter;
import org.cloudfoundry.identity.uaa.test.NullSafeSystemProfileValueSource;
import org.cloudfoundry.identity.uaa.test.TestUtils;
import org.cloudfoundry.identity.uaa.test.UaaTestAccounts;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.common.util.RandomValueStringGenerator;
import org.springframework.security.oauth2.provider.ClientDetails;
import org.springframework.security.oauth2.provider.DefaultAuthorizationRequest;
import org.springframework.test.annotation.ProfileValueSourceConfiguration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.cloudfoundry.identity.uaa.oauth.approval.Approval.ApprovalStatus.APPROVED;
import static org.cloudfoundry.identity.uaa.oauth.approval.Approval.ApprovalStatus.DENIED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

@ContextConfiguration(locations = {"classpath:spring/env.xml", "classpath:spring/data-source.xml"})
@RunWith(SpringJUnit4ClassRunner.class)
@ProfileValueSourceConfiguration(NullSafeSystemProfileValueSource.class)
public class UserManagedAuthzApprovalHandlerTests {

    private final UserManagedAuthzApprovalHandler handler = new UserManagedAuthzApprovalHandler();
    private UaaTestAccounts testAccounts = UaaTestAccounts.standard(null);

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate template;

    @Autowired
    private LimitSqlAdapter limitSqlAdapter;

    private ApprovalStore approvalStore = null;

    private String userId = null;
    private TestAuthentication userAuthentication;

    @Before
    public void setup() {
        template = new JdbcTemplate(dataSource);
        approvalStore = new JdbcApprovalStore(
            template, 
            new JdbcPagingListFactory(template, limitSqlAdapter),
            new SimpleSearchQueryConverter()
        );
        handler.setApprovalStore(approvalStore);
        handler.setClientDetailsService(
            mockClientDetailsService(
                "foo", 
                new String[]{
                    "cloud_controller.read",
                    "cloud_controller.write", 
                    "openid",
                    "space.*.developer"
                }, 
                Collections.<String, Object>emptyMap()
            )
        );
        userId = new RandomValueStringGenerator().generate();
        userAuthentication = new TestAuthentication(userId, testAccounts.getUserName(), true);
    }

    private QueryableResourceManager<ClientDetails> mockClientDetailsService(String id, String[] scope, Map<String, Object> addlInfo) {
        @SuppressWarnings("unchecked")
        QueryableResourceManager<ClientDetails> service = mock(QueryableResourceManager.class);
        ClientDetails details = mock(ClientDetails.class);
        Mockito.when(service.retrieve(id)).thenReturn(details);
        Mockito.when(details.getScope()).thenReturn(new HashSet<>(Arrays.asList(scope)));
        Mockito.when(details.getAdditionalInformation()).thenReturn(addlInfo);
        return service;
    }

    @Test
    public void testNoScopeApproval() {
        DefaultAuthorizationRequest request = new DefaultAuthorizationRequest(new HashMap<String, String>());
        request.setApproved(true);
        // The request is approved but does not request any scopes. The user has
        // also not approved any scopes. Approved.
        assertTrue(handler.isApproved(request, userAuthentication));
    }

    @Test
    public void testNoPreviouslyApprovedScopes() {
        DefaultAuthorizationRequest request = new DefaultAuthorizationRequest(
            "foo", 
            new HashSet<>(
                Arrays.asList("cloud_controller.read", "cloud_controller.write")
            )
        );
        request.setApproved(false);
        // The request needs user approval for scopes. The user has also not
        // approved any scopes prior to this request.
        // Not approved.
        assertFalse(handler.isApproved(request, userAuthentication));
    }

    @Test
    public void testAuthzApprovedButNoPreviouslyApprovedScopes() {
        DefaultAuthorizationRequest request = new DefaultAuthorizationRequest(
            "foo", 
            new HashSet<>(
                Arrays.asList("cloud_controller.read", "cloud_controller.write")
            )
        );
        request.setApproved(true);
        // The request needs user approval for scopes. The user has also not
        // approved any scopes prior to this request.
        // Not approved.
        assertFalse(handler.isApproved(request, userAuthentication));
    }

    @Test
    public void testNoRequestedScopesButSomeApprovedScopes() {
        DefaultAuthorizationRequest request = new DefaultAuthorizationRequest("foo", new HashSet<String>());
        request.setApproved(false);

        long theFuture = System.currentTimeMillis() + (86400 * 7 * 1000);
        Date nextWeek = new Date(theFuture);

        approvalStore.addApproval(new Approval(userAuthentication.getId(), "foo", "cloud_controller.read",nextWeek, APPROVED));
        approvalStore.addApproval(new Approval(userAuthentication.getId(), "foo", "cloud_controller.write",nextWeek, DENIED));

        // The request is approved because the user has not requested any scopes
        assertTrue(handler.isApproved(request, userAuthentication));
        assertEquals(0, request.getScope().size());
    }

    @Test
    public void testRequestedScopesDontMatchApprovalsAtAll() {
        DefaultAuthorizationRequest request = new DefaultAuthorizationRequest(
            "foo", 
            new HashSet<>(
                Arrays.asList("openid")
            )
        );
        request.setApproved(false);

        long theFuture = System.currentTimeMillis() + (86400 * 7 * 1000);
        Date nextWeek = new Date(theFuture);

        approvalStore.addApproval(new Approval(userAuthentication.getId(), "foo", "cloud_controller.read",nextWeek, APPROVED));
        approvalStore.addApproval(new Approval(userAuthentication.getId(), "foo", "cloud_controller.write",nextWeek, DENIED));

        // The request is not approved because the user has not yet approved the
        // scopes requested
        assertFalse(handler.isApproved(request, userAuthentication));
    }

    @Test
    public void testOnlySomeRequestedScopeMatchesApproval() {
        DefaultAuthorizationRequest request = new DefaultAuthorizationRequest(
            "foo", 
            new HashSet<>(
                Arrays.asList("openid", "cloud_controller.read")
            )
        );
        request.setApproved(false);

        long theFuture = System.currentTimeMillis() + (86400 * 7 * 1000);
        Date nextWeek = new Date(theFuture);

        approvalStore.addApproval(new Approval(userAuthentication.getId(), "foo", "cloud_controller.read",nextWeek, APPROVED));
        approvalStore.addApproval(new Approval(userAuthentication.getId(), "foo", "cloud_controller.write",nextWeek, DENIED));

        // The request is not approved because the user has not yet approved all
        // the scopes requested
        assertFalse(handler.isApproved(request, userAuthentication));
    }

    @Test
    public void testOnlySomeRequestedScopeMatchesDeniedApprovalButScopeAutoApproved() {
        DefaultAuthorizationRequest request = new DefaultAuthorizationRequest(
            "foo", 
            new HashSet<>(
                Arrays.asList("openid", "cloud_controller.read")
            )
        );
        request.setApproved(false);

        long theFuture = System.currentTimeMillis() + (86400 * 7 * 1000);
        Date nextWeek = new Date(theFuture);

        handler.setClientDetailsService(
            mockClientDetailsService(
                "foo", 
                new String[]{
                    "cloud_controller.read",
                    "cloud_controller.write", 
                    "openid"
                }, 
                Collections.singletonMap("autoapprove", (Object) "true")
            )
        );

        approvalStore.addApproval(new Approval(userAuthentication.getId(), "foo", "cloud_controller.read",nextWeek, DENIED));
        approvalStore.addApproval(new Approval(userAuthentication.getId(), "foo", "openid", nextWeek, DENIED));

        assertTrue(handler.isApproved(request, userAuthentication));
        assertEquals(new HashSet<>(Arrays.asList(new String[]{"cloud_controller.read", "openid"})),request.getScope());
    }

    @Test
    public void testRequestedScopesMatchApprovalButAdditionalScopesRequested() {
        DefaultAuthorizationRequest request = new DefaultAuthorizationRequest(
            "foo", 
            new HashSet<>(
                Arrays.asList(
                    "openid", 
                    "cloud_controller.read", 
                    "cloud_controller.write"
                )
            )
        );
        request.setApproved(false);

        long theFuture = System.currentTimeMillis() + (86400 * 7 * 1000);
        Date nextWeek = new Date(theFuture);

        approvalStore.addApproval(new Approval(userAuthentication.getId(), "foo", "cloud_controller.read",nextWeek, APPROVED));
        approvalStore.addApproval(new Approval(userAuthentication.getId(), "foo", "cloud_controller.write",nextWeek, DENIED));

        // The request is not approved because the user has not yet approved all
        // the scopes requested
        assertFalse(handler.isApproved(request, userAuthentication));
    }

    @Test
    public void testAllRequestedScopesMatchApproval() {
        DefaultAuthorizationRequest request = new DefaultAuthorizationRequest(
            "foo", 
            new HashSet<>(
                Arrays.asList(
                    "openid", 
                    "cloud_controller.read", 
                    "cloud_controller.write"
                )
            )
        );
        request.setApproved(false);
        long theFuture = System.currentTimeMillis() + (86400 * 7 * 1000);
        Date nextWeek = new Date(theFuture);

        approvalStore.addApproval(new Approval(userId, "foo", "openid", nextWeek, APPROVED));
        approvalStore.addApproval(new Approval(userId, "foo", "cloud_controller.read",nextWeek, APPROVED));
        approvalStore.addApproval(new Approval(userId, "foo", "cloud_controller.write",nextWeek, APPROVED));

        // The request is approved because the user has approved all the scopes
        // requested
        assertTrue(handler.isApproved(request, userAuthentication));
        assertEquals(new HashSet<>(Arrays.asList(new String[]{"openid", "cloud_controller.read","cloud_controller.write"})), request.getScope());
    }

    @Test
    public void testRequestedScopesMatchApprovalButSomeDenied() {
        DefaultAuthorizationRequest request = new DefaultAuthorizationRequest(
            "foo", 
            new HashSet<>(
                Arrays.asList(
                    "openid", 
                    "cloud_controller.read", 
                    "cloud_controller.write"
                )
            )
        );
        request.setApproved(false);
        long theFuture = System.currentTimeMillis() + (86400 * 7 * 1000);
        Date nextWeek = new Date(theFuture);

        approvalStore.addApproval(new Approval(userId, "foo", "openid", nextWeek, APPROVED));
        approvalStore.addApproval(new Approval(userId, "foo", "cloud_controller.read",nextWeek, APPROVED));
        approvalStore.addApproval(new Approval(userId, "foo", "cloud_controller.write",nextWeek, DENIED));

        // The request is approved because the user has acted on all requested
        // scopes
        assertTrue(handler.isApproved(request, userAuthentication));
        assertEquals(new HashSet<>(Arrays.asList(new String[]{"openid", "cloud_controller.read"})),request.getScope());
    }

    @Test
    public void testRequestedScopesMatchApprovalSomeDeniedButDeniedScopesAutoApproved() {
        DefaultAuthorizationRequest request = new DefaultAuthorizationRequest(
            "foo", 
            new HashSet<>(
                Arrays.asList(
                    "openid", 
                    "cloud_controller.read", 
                    "cloud_controller.write"
                )
            )
        );
        request.setApproved(false);
        long theFuture = System.currentTimeMillis() + (86400 * 7 * 1000);
        Date nextWeek = new Date(theFuture);

        handler.setClientDetailsService(mockClientDetailsService(
            "foo",
            new String[]{
                "cloud_controller.read",
                "cloud_controller.write", 
                "openid"
            },
            Collections.singletonMap("autoapprove",(Object) Collections.singletonList("cloud_controller.write"))));

        approvalStore.addApproval(new Approval(userId, "foo", "openid", nextWeek, APPROVED));
        approvalStore.addApproval(new Approval(userId, "foo", "cloud_controller.read",nextWeek, APPROVED));
        approvalStore.addApproval(new Approval(userId, "foo", "cloud_controller.write",nextWeek, DENIED));

        // The request is not approved because the user has denied some of the
        // scopes requested
        assertTrue(handler.isApproved(request, userAuthentication));
        assertThat(
            request.getScope(),
            Matchers.containsInAnyOrder(new String[]{"openid", "cloud_controller.read","cloud_controller.write"})
        );
    }

    @Test
    public void testRequestedScopesMatchApprovalSomeDeniedButDeniedScopesAutoApprovedByWildcard() {
        DefaultAuthorizationRequest request = new DefaultAuthorizationRequest(
            "foo",
            new HashSet<>(
                Arrays.asList(
                    "openid",
                    "cloud_controller.read",
                    "cloud_controller.write",
                    "space.1.developer",
                    "space.2.developer"
                )
            )
        );
        request.setApproved(false);
        long theFuture = System.currentTimeMillis() + (86400 * 7 * 1000);
        Date nextWeek = new Date(theFuture);

        handler.setClientDetailsService(mockClientDetailsService(
            "foo",
            new String[]{
                "cloud_controller.read",
                "cloud_controller.write",
                "openid",
                "space.*.developer"
            },
            Collections.singletonMap("autoapprove",(Object) Arrays.asList("space.*.developer", "cloud_controller.write"))));

        approvalStore.addApproval(new Approval(userId, "foo", "openid", nextWeek, APPROVED));
        approvalStore.addApproval(new Approval(userId, "foo", "cloud_controller.read",nextWeek, APPROVED));
        approvalStore.addApproval(new Approval(userId, "foo", "cloud_controller.write",nextWeek, DENIED));
        approvalStore.addApproval(new Approval(userId, "foo", "space.1.developer",nextWeek, DENIED));

        // The request is not approved because the user has denied some of the
        // scopes requested
        assertTrue(handler.isApproved(request, userAuthentication));
        assertThat(
            request.getScope(),
            Matchers.containsInAnyOrder(new String[]{"openid", "cloud_controller.read","cloud_controller.write","space.1.developer", "space.2.developer"})
        );
    }

    @Test
    public void testRequestedScopesMatchByWildcard() {
        DefaultAuthorizationRequest request = new DefaultAuthorizationRequest(
            "foo",
            new HashSet<>(
                Arrays.asList(
                    "openid",
                    "cloud_controller.read",
                    "cloud_controller.write",
                    "space.1.developer"
                )
            )
        );
        request.setApproved(false);
        long theFuture = System.currentTimeMillis() + (86400 * 7 * 1000);
        Date nextWeek = new Date(theFuture);

        handler.setClientDetailsService(mockClientDetailsService(
            "foo",
            new String[]{
                "cloud_controller.read",
                "cloud_controller.write",
                "openid",
                "space.*.developer"
            },
            Collections.singletonMap("autoapprove", (Object) "true")));

        approvalStore.addApproval(new Approval(userId, "foo", "openid", nextWeek, APPROVED));
        approvalStore.addApproval(new Approval(userId, "foo", "cloud_controller.read",nextWeek, APPROVED));
        approvalStore.addApproval(new Approval(userId, "foo", "cloud_controller.write",nextWeek, DENIED));
        approvalStore.addApproval(new Approval(userId, "foo", "space.1.developer",nextWeek, DENIED));

        // The request is not approved because the user has denied some of the
        // scopes requested
        assertTrue(handler.isApproved(request, userAuthentication));
        assertThat(
            request.getScope(),
            Matchers.containsInAnyOrder(new String[]{"openid", "cloud_controller.read","cloud_controller.write","space.1.developer"})
        );
    }

    @Test
    public void testSomeRequestedScopesMatchApproval() {
        DefaultAuthorizationRequest request = new DefaultAuthorizationRequest(
            "foo", 
            new HashSet<>(Arrays.asList("openid"))
        );
        request.setApproved(false);
        long theFuture = System.currentTimeMillis() + (86400 * 7 * 1000);
        Date nextWeek = new Date(theFuture);

        approvalStore.addApproval(new Approval(userId, "foo", "openid", nextWeek, APPROVED));
        approvalStore.addApproval(new Approval(userId, "foo", "cloud_controller.read",nextWeek, APPROVED));
        approvalStore.addApproval(new Approval(userId, "foo", "cloud_controller.write",nextWeek, APPROVED));

        // The request is approved because the user has approved all the scopes
        // requested
        assertTrue(handler.isApproved(request, userAuthentication));
        assertEquals(new HashSet<>(Arrays.asList(new String[]{"openid"})), request.getScope());
    }

    @After
    public void cleanupDataSource() throws Exception {
        TestUtils.deleteFrom(dataSource, "authz_approvals");
        assertEquals(0, template.queryForInt("select count(*) from authz_approvals"));
    }

    @SuppressWarnings("serial")
    protected static class TestAuthentication extends AbstractAuthenticationToken {
        private final String principal;

        private String name;
        private String id;

        public TestAuthentication(String id, String name, boolean authenticated) {
            super(null);
            setAuthenticated(authenticated);
            this.principal = name;
            this.name = name;
            this.id = id;
        }

        @Override
        public Object getCredentials() {
            return null;
        }

        @Override
        public String getPrincipal() {
            return this.principal;
        }

        @Override
        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getId() {
            return id;
        }

    }

}
