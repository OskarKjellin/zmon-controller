package org.zalando.zauth.zmon.service;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.zalando.stups.oauth2.spring.client.StupsOAuth2RestTemplate;
import org.zalando.stups.oauth2.spring.client.StupsTokensAccessTokenProvider;
import org.zalando.stups.tokens.AccessTokens;
import org.zalando.zauth.zmon.config.ZauthProperties;
import org.zalando.zauth.zmon.domain.Group;
import org.zalando.zmon.security.AuthorityService;
import org.zalando.zmon.security.TeamService;
import org.zalando.zmon.security.authority.ZMonAdminAuthority;
import org.zalando.zmon.security.authority.ZMonAuthority;
import org.zalando.zmon.security.authority.ZMonUserAuthority;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ZauthAuthorityService implements AuthorityService {

    private final Logger log = LoggerFactory.getLogger(ZauthAuthorityService.class);

    private final ZauthProperties zauthProperties;
    private final TeamService teamService;
    private final RestTemplate restTemplate;

    public ZauthAuthorityService(ZauthProperties zauthProperties, TeamService teamService, AccessTokens accessTokens) {
        Preconditions.checkNotNull(zauthProperties.getUserServiceUrl(), "User Service URL must be set");

        this.zauthProperties = zauthProperties;
        this.teamService = teamService;

        restTemplate = new StupsOAuth2RestTemplate(new StupsTokensAccessTokenProvider("user-service", accessTokens));
    }

    private Set<String> getGroups(String username) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(zauthProperties.getUserServiceUrl().toString()).path("/api/employees/" + username + "/groups");

        Group[] groups = restTemplate.getForObject(builder.build().toUri(), Group[].class);
        return Arrays.asList(groups).stream().map(Group::getName).collect(Collectors.toSet());
    }


    @Override
    public Collection<? extends GrantedAuthority> getAuthorities(String username) {
        Set<String> groups = getGroups(username);

        List<ZMonAuthority> result = Lists.newArrayList();
        ZMonAuthority authority = null;
        if (groups.contains(zauthProperties.getAdminsGroup())) {
            authority = new ZMonAdminAuthority(username, ImmutableSet.copyOf(teamService.getTeams(username)));
        } else if (groups.contains(zauthProperties.getUsersGroup())) {
            authority = new ZMonUserAuthority(username, ImmutableSet.copyOf(teamService.getTeams(username)));
        }

        if (authority != null) {
            result = Lists.newArrayList(authority);
            log.info("User {} has authority {} and teams {}", username, authority.getAuthority(),
                    authority.getTeams());
        }

        return result;
    }
}
