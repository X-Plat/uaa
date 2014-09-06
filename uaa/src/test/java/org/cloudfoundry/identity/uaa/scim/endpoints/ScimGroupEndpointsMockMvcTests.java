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
package org.cloudfoundry.identity.uaa.scim.endpoints;

import com.googlecode.flyway.core.Flyway;
import org.cloudfoundry.identity.uaa.config.YamlServletProfileInitializer;
import org.cloudfoundry.identity.uaa.oauth.client.ClientDetailsModification;
import org.cloudfoundry.identity.uaa.rest.SearchResults;
import org.cloudfoundry.identity.uaa.scim.ScimGroup;
import org.cloudfoundry.identity.uaa.scim.ScimGroupExternalMember;
import org.cloudfoundry.identity.uaa.scim.jdbc.JdbcScimGroupExternalMembershipManager;
import org.cloudfoundry.identity.uaa.scim.jdbc.JdbcScimGroupProvisioning;
import org.cloudfoundry.identity.uaa.test.DefaultIntegrationTestConfig;
import org.cloudfoundry.identity.uaa.test.TestClient;
import org.codehaus.jackson.map.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.oauth2.common.util.RandomValueStringGenerator;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class ScimGroupEndpointsMockMvcTests {

    private AnnotationConfigWebApplicationContext webApplicationContext;
    private MockMvc mockMvc;
    private String scimReadToken;
    private String scimWriteToken;
    private RandomValueStringGenerator generator = new RandomValueStringGenerator();
    private List<String> defaultExternalMembers;
    private List<ScimGroupExternalMember> databaseExternalMembers;

    @Before
    public void setUp() throws Exception {
        webApplicationContext = new AnnotationConfigWebApplicationContext();
        webApplicationContext.setServletContext(new MockServletContext());
        new YamlServletProfileInitializer().initialize(webApplicationContext);
        webApplicationContext.register(DefaultIntegrationTestConfig.class);
        webApplicationContext.refresh();
        webApplicationContext.registerShutdownHook();

        FilterChainProxy springSecurityFilterChain = webApplicationContext.getBean("springSecurityFilterChain", FilterChainProxy.class);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilter(springSecurityFilterChain)
                .build();

        TestClient testClient = new TestClient(mockMvc);
        String adminToken = testClient.getClientCredentialsOAuthAccessToken("admin", "adminsecret",
                "clients.read clients.write clients.secret");
        String clientId = generator.generate().toLowerCase();
        String clientSecret = generator.generate().toLowerCase();
        createScimClient(adminToken, clientId, clientSecret);
        scimReadToken = testClient.getClientCredentialsOAuthAccessToken(clientId, clientSecret,"scim.read password.write");
        scimWriteToken = testClient.getClientCredentialsOAuthAccessToken(clientId, clientSecret,"scim.write password.write");

        defaultExternalMembers = (List<String>)webApplicationContext.getBean("defaultExternalMembers");
        databaseExternalMembers = webApplicationContext.getBean(JdbcScimGroupExternalMembershipManager.class).query("");
    }
    
    @After
    public void tearDown() {
        if (webApplicationContext!=null) {
            Flyway flyway = webApplicationContext.getBean(Flyway.class);
            flyway.clean();
            webApplicationContext.destroy();
        }
    }

    @Test
    public void testGetExternalGroups() throws Exception {
        checkGetExternalGroups();
    }

    @Test
    public void testCreateExternalGroupMapUsingName() throws Exception {
        String displayName ="internal.read";
        String externalGroup = "cn=java-developers,ou=scopes,dc=test,dc=com";
        ResultActions result = createGroup(null, displayName, externalGroup);
        result.andExpect(status().isCreated());

        //add the newly added list to our expected list, and check again.
        int previousSize = defaultExternalMembers.size();
        ArrayList<String> list = new ArrayList<>(defaultExternalMembers);
        list.add(displayName+"|"+externalGroup);
        defaultExternalMembers = list;
        assertEquals(previousSize+1, defaultExternalMembers.size());
        checkGetExternalGroups();
    }

    @Test
    public void testCreateExternalGroupMapUsingNameAlreadyExists() throws Exception {
        String displayName ="internal.read";
        String externalGroup = "cn=developers,ou=scopes,dc=test,dc=com";
        ResultActions result = createGroup(null, displayName, externalGroup);
        //we don't throw in JdbcScimGroupExternalMembershipManager.java
        //result.andExpect(status().isConflict());
        result.andExpect(status().isCreated());
    }

    @Test
    public void testCreateExternalGroupMapNameDoesNotExists() throws Exception {
        String displayName ="internal.read"+"sdasdas";
        String externalGroup = "cn=developers,ou=scopes,dc=test,dc=com";
        ResultActions result = createGroup(null, displayName, externalGroup);
        result.andExpect(status().isNotFound());
    }

    @Test
    public void testCreateExternalGroupMapUsingId() throws Exception {
        String displayName ="internal.read";
        String groupId = getGroupId(displayName);
        String externalGroup = "cn=java-developers,ou=scopes,dc=test,dc=com";

        ResultActions result = createGroup(groupId, null, externalGroup);
        result.andExpect(status().isCreated());

        //add the newly added list to our expected list, and check again.
        int previousSize = defaultExternalMembers.size();
        ArrayList<String> list = new ArrayList<>(defaultExternalMembers);
        list.add(displayName+"|"+externalGroup);
        defaultExternalMembers = list;
        assertEquals(previousSize+1, defaultExternalMembers.size());
        checkGetExternalGroups();
    }

    protected ResultActions createGroup(String id, String name, String externalName) throws Exception {
        ScimGroupExternalMember em = new ScimGroupExternalMember();
        if (id!=null) em.setGroupId(id);
        if (externalName!=null) em.setExternalGroup(externalName);
        if (name!=null) em.setDisplayName(name);
        String content = new ObjectMapper().writeValueAsString(em);
        MockHttpServletRequestBuilder post = MockMvcRequestBuilders.post("/Groups/External")
            .header("Authorization", "Bearer " + scimWriteToken)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(APPLICATION_JSON)
            .content(content);

        ResultActions result = mockMvc.perform(post);
        return result;
    }

    @Test
    public void testDeleteExternalGroupMapUsingNameDeprecatedAPI() throws Exception {
        String displayName ="internal.read";
        String externalGroup = "cn=developers,ou=scopes,dc=test,dc=com";
        ScimGroupExternalMember em = new ScimGroupExternalMember();
        em.setDisplayName(displayName);
        em.setExternalGroup(externalGroup);

        MockHttpServletRequestBuilder delete = MockMvcRequestBuilders.delete("/Groups/External/" + displayName + "/" + externalGroup)
            .header("Authorization", "Bearer " + scimWriteToken)
            .accept(APPLICATION_JSON);

        ResultActions result = mockMvc.perform(delete);
        result.andExpect(status().isOk());

        //remove the deleted map from our expected list, and check again.
        int previousSize = defaultExternalMembers.size();
        ArrayList<String> list = new ArrayList<>(defaultExternalMembers);
        assertTrue(list.remove(displayName + "|" + externalGroup));
        defaultExternalMembers = list;
        assertEquals(previousSize-1, defaultExternalMembers.size());
        checkGetExternalGroups();
    }

    @Test
    public void testDeleteExternalGroupMapUsingName() throws Exception {
        String displayName ="internal.read";
        String externalGroup = "cn=developers,ou=scopes,dc=test,dc=com";
        ScimGroupExternalMember em = new ScimGroupExternalMember();
        em.setDisplayName(displayName);
        em.setExternalGroup(externalGroup);

        MockHttpServletRequestBuilder post = MockMvcRequestBuilders.delete("/Groups/External/displayName/" + displayName + "/externalGroup/" + externalGroup)
            .header("Authorization", "Bearer " + scimWriteToken)
            .accept(APPLICATION_JSON);

        ResultActions result = mockMvc.perform(post);
        result.andExpect(status().isOk());

        //remove the deleted map from our expected list, and check again.
        int previousSize = defaultExternalMembers.size();
        ArrayList<String> list = new ArrayList<>(defaultExternalMembers);
        assertTrue(list.remove(displayName + "|" + externalGroup));
        defaultExternalMembers = list;
        assertEquals(previousSize-1, defaultExternalMembers.size());
        checkGetExternalGroups();
    }

    @Test
    public void testDeleteExternalGroupMapUsingIdDeprecatedAPI() throws Exception {
        String displayName ="internal.read";
        String externalGroup = "cn=developers,ou=scopes,dc=test,dc=com";
        String groupId = getGroupId(displayName);

        MockHttpServletRequestBuilder post = MockMvcRequestBuilders.delete("/Groups/External/id/" + groupId + "/" + externalGroup)
            .header("Authorization", "Bearer " + scimWriteToken)
            .accept(APPLICATION_JSON);

        ResultActions result = mockMvc.perform(post);
        result.andExpect(status().isOk());

        //remove the deleted map from our expected list, and check again.
        int previousSize = defaultExternalMembers.size();
        ArrayList<String> list = new ArrayList<>(defaultExternalMembers);
        assertTrue(list.remove(displayName + "|" + externalGroup));
        defaultExternalMembers = list;
        assertEquals(previousSize-1, defaultExternalMembers.size());
        checkGetExternalGroups();
    }

    @Test
    public void testDeleteExternalGroupMapUsingId() throws Exception {
        String displayName ="internal.read";
        String externalGroup = "cn=developers,ou=scopes,dc=test,dc=com";
        String groupId = getGroupId(displayName);

        MockHttpServletRequestBuilder post = MockMvcRequestBuilders.delete("/Groups/External/groupId/" + groupId + "/externalGroup/" + externalGroup)
            .header("Authorization", "Bearer " + scimWriteToken)
            .accept(APPLICATION_JSON);

        ResultActions result = mockMvc.perform(post);
        result.andExpect(status().isOk());

        //remove the deleted map from our expected list, and check again.
        int previousSize = defaultExternalMembers.size();
        ArrayList<String> list = new ArrayList<>(defaultExternalMembers);
        assertTrue(list.remove(displayName + "|" + externalGroup));
        defaultExternalMembers = list;
        assertEquals(previousSize-1, defaultExternalMembers.size());
        checkGetExternalGroups();
    }

    @Test
    public void testDeleteExternalGroupMapUsingReadToken() throws Exception {
        String displayName ="internal.read";
        String externalGroup = "cn=developers,ou=scopes,dc=test,dc=com";
        String groupId = getGroupId(displayName);

        MockHttpServletRequestBuilder post = MockMvcRequestBuilders.delete("/Groups/External/id/" + groupId + "/" + externalGroup)
            .header("Authorization", "Bearer " + scimReadToken)
            .accept(APPLICATION_JSON);

        ResultActions result = mockMvc.perform(post);
        result.andExpect(status().isForbidden());

        checkGetExternalGroups();
    }

    @Test
    public void testGetExternalGroupsFilter() throws Exception {
        checkGetExternalGroupsFilter("displayName", "internal.");
        checkGetExternalGroupsFilter("externalGroup", "o=springsource,o=org");
        checkGetExternalGroupsFilter("groupId", databaseExternalMembers.get(2).getGroupId());

    }

    protected void checkGetExternalGroupsFilter(String fieldName, String fieldValue) throws Exception {
        MockHttpServletRequestBuilder get = MockMvcRequestBuilders.get("/Groups/External")
            .param("filter", fieldName+" co \""+fieldValue+"\"")
            .header("Authorization", "Bearer " + scimReadToken)
            .accept(APPLICATION_JSON);

        ResultActions result = mockMvc.perform(get);
        result.andExpect(status().isOk());
        String content = result.andReturn().getResponse().getContentAsString();
        SearchResults<ScimGroupExternalMember> members = null;

        Map<String,Object> map = new ObjectMapper().readValue(content, Map.class);
        List<Map<String,String>> resources = (List<Map<String,String>>)map.get("resources");
        int startIndex = Integer.parseInt(map.get("startIndex").toString());
        int itemsPerPage = Integer.parseInt(map.get("itemsPerPage").toString());
        int totalResults = Integer.parseInt(map.get("totalResults").toString());
        List<ScimGroupExternalMember> memberList = new ArrayList<>();
        for (Map<String,String> m : resources) {
            ScimGroupExternalMember sgm = new ScimGroupExternalMember();
            sgm.setGroupId(m.get("groupId"));
            sgm.setDisplayName(m.get("displayName"));
            sgm.setExternalGroup(m.get("externalGroup"));
            memberList.add(sgm);
        }
        members = new SearchResults<>((List<String>)map.get("schemas"), memberList, startIndex, itemsPerPage, totalResults);
        assertNotNull(members);

        List<ScimGroupExternalMember> expected = new ArrayList<>();
        for (ScimGroupExternalMember m : databaseExternalMembers) {
            switch (fieldName) {
                case "displayName" : {
                    if (m.getDisplayName().startsWith(fieldValue)) {
                        expected.add(m);
                    }
                    break;
                }
                case "externalGroup" : {
                    if (m.getExternalGroup().contains(fieldValue)) {
                        expected.add(m);
                    }
                    break;
                }
                case "groupId" : {
                    if (m.getGroupId().contains(fieldValue)) {
                        expected.add(m);
                    }
                    break;
                }
            }
        }

        assertEquals(expected.size(), members.getResources().size());
        validateDbMembers(expected, members.getResources().toArray(new ScimGroupExternalMember[0]));
    }

    @Test
    public void testGetExternalGroupsPagination() throws Exception {
        checkGetExternalGroupsPagination(1, 100);
        checkGetExternalGroupsPagination(1, 1);
        checkGetExternalGroupsPagination(2, 1);
        checkGetExternalGroupsPagination(3, 1);
        checkGetExternalGroupsPagination(4, 1);
        checkGetExternalGroupsPagination(4, 10);
        checkGetExternalGroupsPagination(5, 1);
        checkGetExternalGroupsPagination(5, 100);
        checkGetExternalGroupsPagination(6, 0);
        checkGetExternalGroupsPagination(6, 1);
        checkGetExternalGroupsPagination(6, 100);
        checkGetExternalGroupsPagination(6, -1);
        checkGetExternalGroupsPagination(-6, 1);
    }

    protected void checkGetExternalGroupsPagination(int start, int count) throws Exception {
        MockHttpServletRequestBuilder get = MockMvcRequestBuilders.get("/Groups/External")
            .param("startIndex",String.valueOf(start))
            .param("count", String.valueOf(count))
            .header("Authorization", "Bearer " + scimReadToken)
            .accept(APPLICATION_JSON);

        ResultActions result = mockMvc.perform(get);
        result.andExpect(status().isOk());
        String content = result.andReturn().getResponse().getContentAsString();
        SearchResults<ScimGroupExternalMember> members = null;

        Map<String,Object> map = new ObjectMapper().readValue(content, Map.class);
        List<Map<String,String>> resources = (List<Map<String,String>>)map.get("resources");
        int startIndex = Integer.parseInt(map.get("startIndex").toString());
        int itemsPerPage = Integer.parseInt(map.get("itemsPerPage").toString());
        int totalResults = Integer.parseInt(map.get("totalResults").toString());
        List<ScimGroupExternalMember> memberList = new ArrayList<>();
        for (Map<String,String> m : resources) {
            ScimGroupExternalMember sgm = new ScimGroupExternalMember();
            sgm.setGroupId(m.get("groupId"));
            sgm.setDisplayName(m.get("displayName"));
            sgm.setExternalGroup(m.get("externalGroup"));
            memberList.add(sgm);
        }
        members = new SearchResults<>((List<String>)map.get("schemas"), memberList, startIndex, itemsPerPage, totalResults);
        assertNotNull(members);

        if (start<=0) {
            start = 1;
        }
        List<ScimGroupExternalMember> expected = new ArrayList<>();
        for (int i=0; i<count;i++) {
            int idx = start-1+i;
            if (idx>=0 && idx<databaseExternalMembers.size()) {
                expected.add(databaseExternalMembers.get(idx));
            }
        }

        assertEquals(expected.size(), members.getResources().size());
        validateDbMembers(expected, members.getResources().toArray(new ScimGroupExternalMember[0]));
    }

    protected void checkGetExternalGroups() throws Exception {
        String path = "/Groups/External/list";
        checkGetExternalGroups(path);
        path = "/Groups/External";
        checkGetExternalGroups(path);
    }
    protected void checkGetExternalGroups(String path) throws Exception {
        MockHttpServletRequestBuilder get = MockMvcRequestBuilders.get(path)
            .header("Authorization", "Bearer " + scimReadToken)
            .accept(APPLICATION_JSON);

        ResultActions result = mockMvc.perform(get);
        result.andExpect(status().isOk());
        String content = result.andReturn().getResponse().getContentAsString();
        SearchResults<ScimGroupExternalMember> members = null;

        Map<String,Object> map = new ObjectMapper().readValue(content, Map.class);
        List<Map<String,String>> resources = (List<Map<String,String>>)map.get("resources");
        int startIndex = Integer.parseInt(map.get("startIndex").toString());
        int itemsPerPage = Integer.parseInt(map.get("itemsPerPage").toString());
        int totalResults = Integer.parseInt(map.get("totalResults").toString());
        List<ScimGroupExternalMember> memberList = new ArrayList<>();
        for (Map<String,String> m : resources) {
            ScimGroupExternalMember sgm = new ScimGroupExternalMember();
            sgm.setGroupId(m.get("groupId"));
            sgm.setDisplayName(m.get("displayName"));
            sgm.setExternalGroup(m.get("externalGroup"));
            memberList.add(sgm);
        }
        members = new SearchResults<>((List<String>)map.get("schemas"), memberList, startIndex, itemsPerPage, totalResults);
        assertNotNull(members);
        assertEquals(defaultExternalMembers.size(), members.getResources().size());
        validateMembers(defaultExternalMembers, members.getResources().toArray(new ScimGroupExternalMember[0]));
    }

    protected String getGroupId(String displayName) throws Exception {
        JdbcScimGroupProvisioning gp = (JdbcScimGroupProvisioning)webApplicationContext.getBean("scimGroupProvisioning");
        List<ScimGroup> result = gp.query("displayName eq \""+displayName+"\"");
        if (result==null || result.size()==0) {
            throw new NullPointerException("Group not found:"+displayName);
        }
        if (result.size()>1) {
            throw new IllegalStateException("Group name should be unique:"+displayName);
        }
        return result.get(0).getId();
    }

    protected void validateMembers(List<String> expected, ScimGroupExternalMember[] actual) {
        List<ScimGroupExternalMember> members = new ArrayList<>();
        for (String s : expected) {
            String[] data = s.split("\\|");
            assertNotNull(data);
            assertEquals(2, data.length);
            String displayName = data[0];
            String externalId = data[1];
            ScimGroupExternalMember mbr = new ScimGroupExternalMember("N/A", externalId);
            mbr.setDisplayName(displayName);
            members.add(mbr);
        }
        validateDbMembers(members, actual);
    }
    protected void validateDbMembers(List<ScimGroupExternalMember> expected, ScimGroupExternalMember[] actual) {
        for (ScimGroupExternalMember s : expected) {
            String displayName = s.getDisplayName();
            String externalId = s.getExternalGroup();
            boolean found = false;
            for (ScimGroupExternalMember m : actual) {
                assertNotNull("Display name can not be null", m.getDisplayName());
                assertNotNull("External ID can not be null", m.getExternalGroup());
                if (m.getDisplayName().equals(displayName) && m.getExternalGroup().equals(externalId)) {
                    found = true;
                    break;
                }
            }
            assertTrue("Did not find expected external group mapping:"+s,found);
        }
    }

    private void createScimClient(String adminAccessToken, String id, String secret) throws Exception {
        ClientDetailsModification client = new ClientDetailsModification(id, "oauth", "foo,bar", "client_credentials", "scim.read,scim.write,password.write,oauth.approvals");
        client.setClientSecret(secret);
        MockHttpServletRequestBuilder createClientPost = post("/oauth/clients")
                .header("Authorization", "Bearer " + adminAccessToken)
                .accept(APPLICATION_JSON)
                .contentType(APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsBytes(client));
        mockMvc.perform(createClientPost).andExpect(status().isCreated());
    }
}
