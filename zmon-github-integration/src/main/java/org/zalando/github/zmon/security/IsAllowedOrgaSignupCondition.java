package org.zalando.github.zmon.security;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.social.github.api.GitHub;
import org.springframework.web.client.RestOperations;
import org.springframework.web.util.UriTemplate;

/**
 * @author jbellmann
 */
public class IsAllowedOrgaSignupCondition extends GithubSignupCondition {

    /**
     * you can only request the members when you are a member of that
     * organization, otherwise the request returns a 302. We set 'per_page=1' to
     * reduce the data returned by github.
     * 
     * https://developer.github.com/v3/orgs/members/#response-if-requester-is-not-an-organization-member
     */
    private static final String MEMBERS_REQUEST = "https://api.github.com/orgs/{orga}/members?per_page=1";

    private final Logger log = LoggerFactory.getLogger(IsAllowedOrgaSignupCondition.class);

    private final GithubSignupConditionProperties signupProperties;

    public IsAllowedOrgaSignupCondition(final GithubSignupConditionProperties signupProperties) {
        this.signupProperties = signupProperties;
    }

    @Override
    public boolean matches(final GitHub api) {
        if (signupProperties.getAllowedOrgas().isEmpty()) {
            return false;
        }

        if (signupProperties.getAllowedOrgas().contains(ALL_AUTHORIZED)) {
            return true;
        }

        return isOrgaMember(api);
    }

    protected boolean isOrgaMember(GitHub api) {
        RestOperations rest = api.restOperations();
        final String username = api.userOperations().getProfileId();
        for (String orga : signupProperties.getAllowedOrgas()) {
            try {

                log.info("Checking Orga : {}", orga);
                URI uri = new UriTemplate(MEMBERS_REQUEST).expand(orga);
                ResponseEntity<String> response = rest.getForEntity(uri, String.class);
                if (response.getStatusCode().equals(HttpStatus.OK)) {
                    log.info("{} is a member of {}", username, orga);
                    return true;
                }
            } catch (Exception e) {
                log.warn(e.getMessage(), e);
            }
        }

        log.info("{} is not a member of orgas {}", username, signupProperties.getAllowedOrgas());
        return false;
    }

}
