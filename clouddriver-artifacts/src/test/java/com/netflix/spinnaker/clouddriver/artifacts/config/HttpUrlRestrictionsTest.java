package com.netflix.spinnaker.clouddriver.artifacts.config;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class HttpUrlRestrictionsTest {

  @Test
  public void verifyThatIpRangesBlockAccess() {
    var restrictions =
        HttpUrlRestrictions.builder()
            .rejectVerbatimIps(false)
            .rejectedIps(List.of("192.168.0.0/16", "10.0.0.0/8"))
            .build();
    assertThrows(
        IllegalArgumentException.class,
        () -> restrictions.validateURI(HttpUrl.parse("http://192.168.0.1")));
    assertThrows(
        IllegalArgumentException.class,
        () -> restrictions.validateURI(HttpUrl.parse("http://10.2.3.4")));
  }

  @Test
  public void blockVerbatimIpsWhenNoIpListSet() {
    var restrictions =
        HttpUrlRestrictions.builder().rejectVerbatimIps(true).rejectedIps(List.of()).build();
    assertThrows(
        IllegalArgumentException.class,
        () -> restrictions.validateURI(HttpUrl.parse("http://192.168.0.1")));
    assertThrows(
        IllegalArgumentException.class,
        () -> restrictions.validateURI(HttpUrl.parse("http://10.2.3.4")));
  }

  @Test
  public void testWHenNoAllowedRegexButWhiteListIsSet() {
    var restrictions =
        HttpUrlRestrictions.builder()
            .allowedHostnamesRegex("")
            .allowedDomains(List.of("google.com"))
            .build();
    assertThat(restrictions.validateURI(HttpUrl.parse("http://google.com"))).hasHost("google.com");
    assertThrows(
        IllegalArgumentException.class,
        () -> restrictions.validateURI(HttpUrl.parse("http://microsoft.com")));
  }

  @Test
  public void allowIpsWhenVerbatimIpsIsFalse() {
    var restrictions =
        HttpUrlRestrictions.builder().rejectVerbatimIps(false).rejectedIps(List.of()).build();
    assertThat(restrictions.validateURI(HttpUrl.parse("http://192.168.0.1")))
        .hasHost("192.168.0.1");
  }

  @Test
  public void blockIPRangesWhenResolved() {
    var restrictions =
        HttpUrlRestrictions.builder()
            .rejectVerbatimIps(true)
            .rejectedIps(List.of("10.0.0.0/8"))
            .build();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            restrictions.validateURI(
                HttpUrl.parse(
                    "http://0a010203.0a010204.rbndr.us"))); // Make sure a host lookup that returns
    // a 10. address ALSO fails when
    // restricted.
    assertThat(restrictions.validateURI(HttpUrl.parse("http://google.com"))).hasHost("google.com");
  }

  @Test
  public void whiteListBlockEverythingElse() {
    var restrictions = HttpUrlRestrictions.builder().allowedDomains(List.of("example.com")).build();
    assertThrows(
        IllegalArgumentException.class,
        () -> restrictions.validateURI(HttpUrl.parse("http://google.com")));
    assertThat(restrictions.validateURI(HttpUrl.parse("http://example.com")))
        .hasHost("example.com");
  }

  @Test
  public void allowAHostIfResolvesInIpList() {
    var restrictions =
        HttpUrlRestrictions.builder()
            .rejectVerbatimIps(true)
            .rejectedIps(List.of("192.168.0.0/16"))
            .build();
    assertThat(restrictions.validateURI(HttpUrl.parse("http://0a010203.0a010204.rbndr.us")))
        .hasHost("0a010203.0a010204.rbndr.us");
    assertThat(restrictions.validateURI(HttpUrl.parse("http://google.com"))).hasHost("google.com");
  }

  @Test
  void blockDefaultRestrictedDomains() {
    // explicitly deny the test server we're hitting.
    HttpUrlRestrictions restrictions = HttpUrlRestrictions.builder().build();
    List<String> invalidDomains =
        List.of(
            "http://spin-clouddriver",
            "http://spin-clouddriver.prod",
            "http://spin-clouddriver.spinnaker.svc.cluster.local",
            "http://spin-clouddriver.spinnaker",
            "http://spinnaker-clouddriver:12345",
            "http://spinnaker-clouddriver.spinnaker:12345",
            "http://spin-clouddriver.local");
    invalidDomains.forEach(
        domain -> {
          Assertions.assertThrows(
              IllegalArgumentException.class,
              () -> restrictions.validateURI(HttpUrl.parse(domain)));
        });

    assertThat(restrictions.validateURI(HttpUrl.parse("http://example.com")))
        .hasHost("example.com");
    assertThat(restrictions.validateURI(HttpUrl.parse("http://0a010203.0a010204.rbndr.us")))
        .hasHost("0a010203.0a010204.rbndr.us");
  }

  @Test
  public void rejectAuthorityBypassAttempt() {
    // Test that URLs with userinfo (username:password@) in authority don't bypass validation
    // The old vulnerable code would extract "example.com" from userinfo instead of the actual host
    var restrictions = HttpUrlRestrictions.builder().allowedDomains(List.of("example.com")).build();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            restrictions.validateURI(
                HttpUrl.parse("http://example.com:password@some_underscore_host.com")));
  }

  @Test
  public void allowLegitimateUnderscoreHostsWhenAllowed() {
    // Test that when the actual target host IS in the allowed domains list,
    // the URL passes validation (proves HttpUrl correctly extracts the real host)
    var restrictions = HttpUrlRestrictions.builder().allowedDomains(List.of("google.com")).build();
    // With HttpUrl, the actual host is correctly extracted as google.com (not "example.com" from
    // userinfo)
    // This URL has userinfo "example.com:badpassword" but actual host is "google.com"
    assertThat(restrictions.validateURI(HttpUrl.parse("http://example.com:badpassword@google.com")))
        .hasHost("google.com");
  }
}
