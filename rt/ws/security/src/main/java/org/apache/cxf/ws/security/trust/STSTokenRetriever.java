/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cxf.ws.security.trust;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.w3c.dom.Element;

import org.apache.cxf.common.logging.LogUtils;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.message.Message;
import org.apache.cxf.rt.security.utils.SecurityUtils;
import org.apache.cxf.ws.addressing.AddressingProperties;
import org.apache.cxf.ws.security.SecurityConstants;
import org.apache.cxf.ws.security.tokenstore.SecurityToken;
import org.apache.wss4j.policy.model.Trust10;
import org.apache.wss4j.policy.model.Trust13;

/**
 * A Helper utility class to cache STS token and issue or renew the token from STS.
 */
public final class STSTokenRetriever {
    private static final Logger LOG = LogUtils.getL7dLogger(STSTokenRetriever.class);
    private static final String ASSOCIATED_TOKEN =
        STSTokenRetriever.class.getName() + "-" + "Associated_Token";

    private STSTokenRetriever() {
    }

    public static SecurityToken getToken(Message message, TokenRequestParams params) {
        return getToken(message, params, new DefaultSTSTokenCacher());
    }

    public static SecurityToken getToken(Message message, TokenRequestParams params, STSTokenCacher tokenCacher) {
        SecurityToken tok = tokenCacher.retrieveToken(message);
        if (tok == null) {
            tok = issueToken(message, params, tokenCacher);
        } else {
            tok = renewToken(message, tok, params, tokenCacher);
        }

        tokenCacher.storeToken(message, tok);

        return tok;
    }

    private static SecurityToken issueToken(Message message, TokenRequestParams params, STSTokenCacher tokenCacher) {
        AddressingProperties maps =
            (AddressingProperties)message
                .get("javax.xml.ws.addressing.context.outbound");
        if (maps == null) {
            maps = (AddressingProperties)message
                .get("javax.xml.ws.addressing.context");
        }
        STSClient client = STSUtils.getClientWithIssuer(message, "sts", params.getIssuer());
        synchronized (client) {
            try {
                // Transpose ActAs/OnBehalfOf info from original request to the STS client.
                Object token =
                    SecurityUtils.getSecurityPropertyValue(SecurityConstants.STS_TOKEN_ACT_AS, message);
                if (token != null) {
                    client.setActAs(token);
                }
                token =
                    SecurityUtils.getSecurityPropertyValue(SecurityConstants.STS_TOKEN_ON_BEHALF_OF, message);
                if (token != null) {
                    client.setOnBehalfOf(token);
                }
                Map<String, Object> ctx = client.getRequestContext();
                mapSecurityProps(message, ctx);

                Object o = SecurityUtils.getSecurityPropertyValue(SecurityConstants.STS_APPLIES_TO, message);
                String appliesTo = o == null ? null : o.toString();
                if (appliesTo == null) {
                    String endpointAddress =
                        message.getContextualProperty(Message.ENDPOINT_ADDRESS).toString();
                    // Strip out any query parameters if they exist
                    int query = endpointAddress.indexOf('?');
                    if (query > 0) {
                        endpointAddress = endpointAddress.substring(0, query);
                    }
                    appliesTo = endpointAddress;
                }
                boolean enableAppliesTo = client.isEnableAppliesTo();

                client.setMessage(message);
                Element onBehalfOfToken = client.getOnBehalfOfToken();
                Element actAsToken = client.getActAsToken();

                String key = appliesTo;
                if (!enableAppliesTo || key == null || "".equals(key)) {
                    key = ASSOCIATED_TOKEN;
                }
                // See if the token corresponding to the OnBehalfOf/ActAs Token is stored in the cache
                // and if it points to an issued token
                SecurityToken secToken = tokenCacher.retrieveToken(message, onBehalfOfToken, key);
                if (secToken == null) {
                    secToken = tokenCacher.retrieveToken(message, actAsToken, key);
                }
                if (secToken != null) {
                    // Check to see whether the delegated token needs to be renewed
                    secToken = renewToken(message, secToken, params, tokenCacher);
                } else {
                    secToken = getTokenFromSTS(client, maps, appliesTo, params);
                }

                if (secToken != null) {
                    tokenCacher.storeToken(message, onBehalfOfToken, secToken.getId(), key);
                    tokenCacher.storeToken(message, actAsToken, secToken.getId(), key);
                }
                return secToken;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new Fault(e);
            } finally {
                client.setTrust((Trust10)null);
                client.setTrust((Trust13)null);
                client.setTemplate(null);
                client.setAddressingNamespace(null);
            }
        }
    }

    private static SecurityToken renewToken(
                                     Message message,
                                     SecurityToken tok,
                                     TokenRequestParams params,
                                     STSTokenCacher tokenCacher) {
        String imminentExpiryValue =
            (String)SecurityUtils.getSecurityPropertyValue(SecurityConstants.STS_TOKEN_IMMINENT_EXPIRY_VALUE,
                                                           message);
        long imminentExpiry = 10L;
        if (imminentExpiryValue != null) {
            imminentExpiry = Long.parseLong(imminentExpiryValue);
        }

        // If the token has not expired then we don't need to renew it
        if (!(tok.isExpired() || tok.isAboutToExpire(imminentExpiry))) {
            return tok;
        }

        // Remove token from cache
        tokenCacher.removeToken(message, tok);

        // If the user has explicitly disabled Renewing then we can't renew a token,
        // so just get a new one
        STSClient client = STSUtils.getClientWithIssuer(message, "sts", params.getIssuer());
        if (!client.isAllowRenewing()) {
            return issueToken(message, params, tokenCacher);
        }

        AddressingProperties maps =
            (AddressingProperties)message
                .get("javax.xml.ws.addressing.context.outbound");
        if (maps == null) {
            maps = (AddressingProperties)message
                .get("javax.xml.ws.addressing.context");
        }
        synchronized (client) {
            try {
                Map<String, Object> ctx = client.getRequestContext();
                mapSecurityProps(message, ctx);

                client.setMessage(message);

                if (maps != null) {
                    client.setAddressingNamespace(maps.getNamespaceURI());
                }

                client.setTrust(params.getTrust10());
                client.setTrust(params.getTrust13());

                client.setTemplate(params.getTokenTemplate());
                return client.renewSecurityToken(tok);
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "Error renewing a token", ex);
                boolean issueAfterFailedRenew =
                    SecurityUtils.getSecurityPropertyBoolean(
                                              SecurityConstants.STS_ISSUE_AFTER_FAILED_RENEW, message, true);
                if (issueAfterFailedRenew) {
                    // Perhaps the STS does not support renewing, so try to issue a new token
                    return issueToken(message, params, tokenCacher);
                } else {
                    throw ex;
                }
            } catch (Exception ex) {
                LOG.log(Level.WARNING, "Error renewing a token", ex);
                boolean issueAfterFailedRenew =
                    SecurityUtils.getSecurityPropertyBoolean(
                                              SecurityConstants.STS_ISSUE_AFTER_FAILED_RENEW, message, true);
                if (issueAfterFailedRenew) {
                    // Perhaps the STS does not support renewing, so try to issue a new token
                    return issueToken(message, params, tokenCacher);
                } else {
                    throw new Fault(ex);
                }
            } finally {
                client.setTrust((Trust10)null);
                client.setTrust((Trust13)null);
                client.setTemplate(null);
                client.setAddressingNamespace(null);
            }
        }
    }

    private static void mapSecurityProps(Message message, Map<String, Object> ctx) {
        for (String s : SecurityConstants.ALL_PROPERTIES) {
            Object v = message.getContextualProperty(s + ".it");
            if (v == null) {
                v = message.getContextualProperty(s);
            }
            if (!ctx.containsKey(s) && v != null) {
                ctx.put(s, v);
            }
        }
    }

    private static SecurityToken getTokenFromSTS(STSClient client,
                                          AddressingProperties maps, String appliesTo,
                                          TokenRequestParams params) throws Exception {
        client.setTrust(params.getTrust10());
        client.setTrust(params.getTrust13());
        client.setTemplate(params.getTokenTemplate());
        if (params.getWspNamespace() != null) {
            client.setWspNamespace(params.getWspNamespace());
        }
        if (maps != null && maps.getNamespaceURI() != null) {
            client.setAddressingNamespace(maps.getNamespaceURI());
        }
        if (params.getClaims() != null) {
            client.setClaims(params.getClaims());
        }
        return client.requestSecurityToken(appliesTo);
    }

    public static class TokenRequestParams {
        private Element issuer;
        private Trust10 trust10;
        private Trust13 trust13;
        private Element tokenTemplate;
        private String wspNamespace;
        private Element claims;

        public Element getIssuer() {
            return issuer;
        }

        public void setIssuer(Element issuer) {
            this.issuer = issuer;
        }

        public Trust10 getTrust10() {
            return trust10;
        }

        public void setTrust10(Trust10 trust10) {
            this.trust10 = trust10;
        }

        public Trust13 getTrust13() {
            return trust13;
        }

        public void setTrust13(Trust13 trust13) {
            this.trust13 = trust13;
        }

        public Element getTokenTemplate() {
            return tokenTemplate;
        }

        public void setTokenTemplate(Element tokenTemplate) {
            this.tokenTemplate = tokenTemplate;
        }

        public String getWspNamespace() {
            return wspNamespace;
        }

        public void setWspNamespace(String wspNamespace) {
            this.wspNamespace = wspNamespace;
        }

        public Element getClaims() {
            return claims;
        }

        public void setClaims(Element claims) {
            this.claims = claims;
        }
    }
}