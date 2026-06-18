/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.aws.security.config

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.ec2.model.DescribeRegionsResult
import com.amazonaws.services.ec2.model.DescribeAvailabilityZonesResult
import com.amazonaws.services.ec2.model.AvailabilityZone
import com.netflix.spinnaker.clouddriver.aws.security.AWSAccountInfoLookup
import com.netflix.spinnaker.clouddriver.aws.security.AmazonClientProvider
import com.netflix.spinnaker.clouddriver.aws.security.AmazonCredentials
import com.netflix.spinnaker.clouddriver.aws.security.NetflixAmazonCredentials
import com.netflix.spinnaker.clouddriver.aws.security.NetflixAssumeRoleAmazonCredentials
import com.netflix.spinnaker.clouddriver.aws.security.config.AccountsConfiguration.Account
import com.netflix.spinnaker.clouddriver.aws.security.config.CredentialsConfig.LifecycleHook
import com.netflix.spinnaker.clouddriver.aws.security.config.CredentialsConfig.Region
import spock.lang.Specification

class CredentialsLoaderSpec extends Specification {

    def 'basic test with defaults'() {
        setup:
        def config = new CredentialsConfig(defaultRegions: [
                new Region(name: 'us-east-1', availabilityZones: ['us-east-1c', 'us-east-1d', 'us-east-1e']),
                new Region(name: 'us-west-2', availabilityZones: ['us-west-2a', 'us-west-2b'])],
                defaultKeyPairTemplate: 'nf-{{name}}-keypair-a',
                defaultEddaTemplate: 'http://edda-main.%s.{{name}}.netflix.net',
                defaultFront50Template: 'http://front50.prod.netflix.net/{{name}}',
                defaultDiscoveryTemplate: 'http://%s.discovery{{name}}.netflix.net',
                defaultAssumeRole: 'role/asgard'
        )
        def accountsConfig = new AccountsConfiguration( accounts: [
          new Account(name: 'test', accountId: 12345, regions: [
            new Region(name: 'us-west-2', deprecated: true)
          ]),
          new Account(name: 'prod', accountId: 67890)
        ])

        AWSCredentialsProvider provider = Mock(AWSCredentialsProvider)
        AmazonClientProvider amazonClientProvider = Mock(AmazonClientProvider)
        AmazonCredentialsParser<Account, NetflixAmazonCredentials> ci = new AmazonCredentialsParser<>(
          provider, amazonClientProvider, NetflixAmazonCredentials.class, config, accountsConfig)

        when:
        List<NetflixAmazonCredentials> creds = ci.load(config)

        then:
        creds.size() == 2
        with(creds.find { it.name == 'prod' }) { AmazonCredentials cred ->
            cred.accountId == "67890"
            cred.defaultKeyPair == 'nf-prod-keypair-a'
            cred.regions.size() == 2
            cred.regions.find { it.name == 'us-east-1' }.availabilityZones.size() == 3
            cred.regions.find { it.name == 'us-east-1' }.deprecated == false
            cred.regions.find { it.name == 'us-west-2' }.availabilityZones.size() == 2
            cred.regions.find { it.name == 'us-west-2' }.deprecated == false
            cred.credentialsProvider == provider
        }
        with(creds.find { it.name == 'test' }) { AmazonCredentials cred ->
          cred.accountId == "12345"
          cred.defaultKeyPair == 'nf-test-keypair-a'
          cred.regions.size() == 1
          cred.regions.find { it.name == 'us-west-2' }.availabilityZones.size() == 2
          cred.regions.find { it.name == 'us-west-2' }.deprecated == true
          cred.credentialsProvider == provider
        }
        0 * _
    }

    def 'account resolves defaults'() {
        setup:
        def config = new CredentialsConfig()
        def accountsConfig = new AccountsConfiguration(accounts: [new Account(name: 'default')])

        AWSCredentialsProvider provider = Mock(AWSCredentialsProvider)
        AWSAccountInfoLookup lookup = Mock(AWSAccountInfoLookup)
        AmazonCredentialsParser<Account, NetflixAmazonCredentials> ci = new AmazonCredentialsParser<>(
          provider, lookup, NetflixAmazonCredentials.class, config, accountsConfig)

        when:
        List<NetflixAmazonCredentials> creds = ci.load(config)

        then:
        1 * lookup.findAccountId() >> 696969
        1 * lookup.listRegions() >> [new AmazonCredentials.AWSRegion('us-east-1', ['us-east-1a', 'us-east-1b'])]
        creds.size() == 1
        with (creds.first()) { AmazonCredentials cred ->
            cred.name == 'default'
            cred.accountId == "696969"
            cred.credentialsProvider == provider
            cred.defaultKeyPair == null
            cred.regions.size() == 1
            cred.regions.first().name == 'us-east-1'
            cred.regions.first().availabilityZones.toList().sort() == ['us-east-1a', 'us-east-1b']
        }
        0 * _
    }
//
    def 'availibilityZones are resolved in default regions only once'() {
        setup:
        def config = new CredentialsConfig(defaultRegions: [new Region(name: 'us-east-1'), new Region(name: 'us-west-2')])
        def accountsConfig = new AccountsConfiguration(accounts: [
          new Account(name: 'default', accountId: 1), new Account(name: 'other', accountId: 2)
        ])
        AWSCredentialsProvider provider = Mock(AWSCredentialsProvider)
        AWSAccountInfoLookup lookup = Mock(AWSAccountInfoLookup)
        AmazonCredentialsParser<Account, NetflixAmazonCredentials> ci = new AmazonCredentialsParser<>(
          provider, lookup, NetflixAmazonCredentials.class, config, accountsConfig)

        when:
        List<AmazonCredentials> creds = ci.load(config)

        then:
        1 * lookup.listRegions(['us-east-1', 'us-west-2']) >> [new AmazonCredentials.AWSRegion('us-east-1', ['us-east-1a']), new AmazonCredentials.AWSRegion('us-west-2', ['us-west-2a'])]
        creds.size() == 2
        with (creds.find { it.name == 'default' }) { AmazonCredentials cred ->
            cred.regions.size() == 2
            cred.regions.toList().sort { it.name }.name == ['us-east-1', 'us-west-2']
        }
        0 * _
    }

    def 'availabilityZones are resolved for account-specific region if not defined in defaults'() {
        def config = new CredentialsConfig(defaultRegions: [new Region(name: 'us-east-1')])

        def accountsConfig = new AccountsConfiguration(accounts: [
          new Account(
            name: 'default',
            accountId: 1,
            regions: [ new Region(name: 'us-west-2')])]
        )
        AWSCredentialsProvider provider = Mock(AWSCredentialsProvider)
        AWSAccountInfoLookup lookup = Mock(AWSAccountInfoLookup)
      AmazonCredentialsParser<Account, NetflixAmazonCredentials> ci = new AmazonCredentialsParser<>(
        provider, lookup, NetflixAmazonCredentials.class, config, accountsConfig)

        when:
        List<AmazonCredentials> creds = ci.load(config)

        then:
        1 * lookup.listRegions(['us-east-1']) >> [new AmazonCredentials.AWSRegion('us-east-1', ['us-east-1a'])]
        1 * lookup.listRegions(['us-west-2']) >> [new AmazonCredentials.AWSRegion('us-west-2', ['us-west-2a'])]
        creds.size() == 1
        with(creds.first()) { AmazonCredentials cred ->
            cred.regions.size() == 1
            cred.regions.first().name == 'us-west-2'
            cred.regions.first().availabilityZones == ['us-west-2a']
        }
    }

    def 'account overrides defaults'() {
        setup:
        def config = new CredentialsConfig(defaultRegions: [
                new Region(name: 'us-east-1', availabilityZones: ['us-east-1c', 'us-east-1d', 'us-east-1e']),
                new Region(name: 'us-west-2', availabilityZones: ['us-west-2a', 'us-west-2b'])],
                defaultKeyPairTemplate: 'nf-{{name}}-keypair-a',
                defaultEddaTemplate: 'http://edda-main.%s.{{name}}.netflix.net',
                defaultFront50Template: 'http://front50.prod.netflix.net/{{name}}',
                defaultDiscoveryTemplate: 'http://%s.discovery{{name}}.netflix.net',
                defaultAssumeRole: 'role/asgard',
                defaultLifecycleHookRoleARNTemplate: 'arn:aws:iam::{{accountId}}:role/my-notification-role',
                defaultLifecycleHookNotificationTargetARNTemplate: 'arn:aws:sns:{{region}}:{{accountId}}:my-sns-topic'
        )
        def accountsConfig = new AccountsConfiguration(accounts: [
          new Account(
            name: 'test',
            accountId: 12345,
            regions: [new Region(name: 'us-west-1', availabilityZones: ['us-west-1a'])],
            discovery: 'us-west-1.discoveryqa.netflix.net',
            eddaEnabled: false,
            defaultKeyPair: 'oss-{{accountId}}-keypair',
            lifecycleHooks: [
              new LifecycleHook(
                lifecycleTransition: 'autoscaling:EC2_INSTANCE_TERMINATING',
                heartbeatTimeout: 1800,
                defaultResult: 'CONTINUE'
              )
            ])
        ])
        AWSCredentialsProvider provider = Mock(AWSCredentialsProvider)
        AWSAccountInfoLookup lookup = Mock(AWSAccountInfoLookup)
        AmazonCredentialsParser<Account, NetflixAmazonCredentials> ci = new AmazonCredentialsParser<>(
          provider, lookup, NetflixAmazonCredentials.class, config, accountsConfig)

        when:
        List<NetflixAmazonCredentials> creds = ci.load(config)

        then:
        creds.size() == 1
        with(creds.first()) { NetflixAmazonCredentials cred ->
            cred.name == 'test'
            cred.accountId == "12345"
            cred.defaultKeyPair == 'oss-12345-keypair'
            cred.discovery == 'us-west-1.discoveryqa.netflix.net'
            cred.discoveryEnabled
            cred.edda == 'http://edda-main.%s.test.netflix.net'
            !cred.eddaEnabled
            cred.front50 == 'http://front50.prod.netflix.net/test'
            cred.front50Enabled
            cred.regions.size() == 1
            cred.regions.first().name == 'us-west-1'
            cred.regions.first().availabilityZones == ['us-west-1a']
            cred.credentialsProvider == provider
            cred.lifecycleHooks.size() == 1
            cred.lifecycleHooks.first().roleARN == 'arn:aws:iam::12345:role/my-notification-role'
            cred.lifecycleHooks.first().notificationTargetARN == 'arn:aws:sns:{{region}}:12345:my-sns-topic'
            cred.lifecycleHooks.first().lifecycleTransition == 'autoscaling:EC2_INSTANCE_TERMINATING'
            cred.lifecycleHooks.first().heartbeatTimeout == 1800
            cred.lifecycleHooks.first().defaultResult == 'CONTINUE'
        }
        0 * _
    }

    def 'accountId must be provided for assumeRole account types'() {
        setup:
        def config = new CredentialsConfig(
                defaultRegions: [new Region(name: 'us-east-1', availabilityZones: ['us-east-1a'])])

        def accountsConfig = new AccountsConfiguration(accounts: [new Account(name: 'gonnaFail')])
        AWSCredentialsProvider provider = Mock(AWSCredentialsProvider)
        AWSAccountInfoLookup lookup = Mock(AWSAccountInfoLookup)
        AmazonCredentialsParser<Account, NetflixAssumeRoleAmazonCredentials> ci = new AmazonCredentialsParser<>(
          provider, lookup, NetflixAssumeRoleAmazonCredentials.class, config, accountsConfig)

        when:
        ci.load(config)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.getMessage().startsWith'accountId is required'
        0 * _
    }

  def 'assumeRole account type overrides defaults'() {
    setup:
    def config = new CredentialsConfig(defaultRegions: [
      new Region(name: 'us-west-2', availabilityZones: ['us-west-2a', 'us-west-2b'])],
      defaultKeyPairTemplate: 'nf-{{name}}-keypair-a',
      defaultAssumeRole: 'role/asgard',
      defaultSessionName: 'spinnaker',
      defaultLifecycleHookRoleARNTemplate: 'arn:aws:iam::{{accountId}}:role/my-notification-role',
      defaultLifecycleHookNotificationTargetARNTemplate: 'arn:aws:sns:{{region}}:{{accountId}}:my-sns-topic'
    )

    def accountsConfig = new AccountsConfiguration(accounts: [
      new Account(
        name: 'test',
        accountId: 12345,
        regions: [new Region(name: 'us-west-1', availabilityZones: ['us-west-1a'])],
        defaultKeyPair: 'oss-{{accountId}}-keypair',
        assumeRole: 'role/spinnakerManaged',
        externalId: '56789',
        sessionName: 'spinnakerManaged',
        lifecycleHooks: [
          new LifecycleHook(
            lifecycleTransition: 'autoscaling:EC2_INSTANCE_TERMINATING',
            heartbeatTimeout: 1800,
            defaultResult: 'CONTINUE'
          )
        ])
    ])

    AWSCredentialsProvider provider = Mock(AWSCredentialsProvider)
    AWSAccountInfoLookup lookup = Mock(AWSAccountInfoLookup)
    AmazonCredentialsParser<Account, NetflixAssumeRoleAmazonCredentials> ci = new AmazonCredentialsParser<>(
      provider, lookup, NetflixAssumeRoleAmazonCredentials.class, config, accountsConfig)

    when:
    List<NetflixAssumeRoleAmazonCredentials> creds = ci.load(config)

    then:
    creds.size() == 1
    with(creds.first()) { NetflixAssumeRoleAmazonCredentials cred ->
      cred.name == 'test'
      cred.accountId == "12345"
      cred.defaultKeyPair == 'oss-12345-keypair'
      cred.regions.size() == 1
      cred.regions.first().name == 'us-west-1'
      cred.regions.first().availabilityZones == ['us-west-1a']
      cred.assumeRole == 'role/spinnakerManaged'
      cred.externalId == '56789'
      cred.sessionName == 'spinnakerManaged'
      cred.lifecycleHooks.size() == 1
      cred.lifecycleHooks.first().roleARN == 'arn:aws:iam::12345:role/my-notification-role'
      cred.lifecycleHooks.first().notificationTargetARN == 'arn:aws:sns:{{region}}:12345:my-sns-topic'
      cred.lifecycleHooks.first().lifecycleTransition == 'autoscaling:EC2_INSTANCE_TERMINATING'
      cred.lifecycleHooks.first().heartbeatTimeout == 1800
      cred.lifecycleHooks.first().defaultResult == 'CONTINUE'
    }
    0 * _
  }

  def 'assumeRole account type test with defaults'() {
    setup:
    def config = new CredentialsConfig(defaultRegions: [
      new Region(name: 'us-east-1', availabilityZones: ['us-east-1c', 'us-east-1d', 'us-east-1e']),
      new Region(name: 'us-west-2', availabilityZones: ['us-west-2a', 'us-west-2b'])],
      defaultKeyPairTemplate: 'nf-{{name}}-keypair-a',
      defaultEddaTemplate: 'http://edda-main.%s.{{name}}.netflix.net',
      defaultFront50Template: 'http://front50.prod.netflix.net/{{name}}',
      defaultDiscoveryTemplate: 'http://%s.discovery{{name}}.netflix.net',
      defaultAssumeRole: 'role/asgard',
      defaultSessionName: 'spinnaker'
    )

    def accountsConfig = new AccountsConfiguration(accounts: [
      new Account(name: 'prod', accountId: 67890)
    ])
    AWSCredentialsProvider provider = Mock(AWSCredentialsProvider)
    AWSAccountInfoLookup lookup = Mock(AWSAccountInfoLookup)
    AmazonCredentialsParser<Account, NetflixAssumeRoleAmazonCredentials> ci = new AmazonCredentialsParser<>(
      provider, lookup, NetflixAssumeRoleAmazonCredentials.class, config, accountsConfig)

    when:
    List<NetflixAssumeRoleAmazonCredentials> creds = ci.load(config)

    then:
    creds.size() == 1
    with(creds.find { it.name == 'prod' }) { NetflixAssumeRoleAmazonCredentials cred ->
      cred.accountId == "67890"
      cred.defaultKeyPair == 'nf-prod-keypair-a'
      cred.regions.size() == 2
      cred.regions.find { it.name == 'us-east-1' }.availabilityZones.size() == 3
      cred.regions.find { it.name == 'us-east-1' }.deprecated == false
      cred.regions.find { it.name == 'us-west-2' }.availabilityZones.size() == 2
      cred.regions.find { it.name == 'us-west-2' }.deprecated == false
      cred.assumeRole == 'role/asgard'
      cred.externalId == null
      cred.sessionName == 'spinnaker'
    }
    0 * _
  }

  // ---------------------------------------------------------------------------
  // useAccountRegions tests
  // ---------------------------------------------------------------------------

  def 'useAccountRegions=false (default) uses shared lookup for listRegions'() {
    given: 'flag is off — existing behaviour: shared lookup drives everything'
    def config = new CredentialsConfig(
      useAccountRegions: false,
      defaultRegions: [new Region(name: 'us-east-1'), new Region(name: 'us-west-2')]
    )
    def accountsConfig = new AccountsConfiguration(accounts: [
      new Account(name: 'test', accountId: '111')
    ])
    AWSCredentialsProvider provider = Mock(AWSCredentialsProvider)
    AWSAccountInfoLookup lookup = Mock(AWSAccountInfoLookup)
    AmazonCredentialsParser<Account, NetflixAmazonCredentials> ci = new AmazonCredentialsParser<>(
      provider, lookup, NetflixAmazonCredentials.class, config, accountsConfig)

    when:
    List<NetflixAmazonCredentials> creds = ci.load(config)

    then: 'listRegions called on the shared lookup — no per-account lookup created'
    1 * lookup.listRegions(['us-east-1', 'us-west-2']) >> [
      new AmazonCredentials.AWSRegion('us-east-1', ['us-east-1a']),
      new AmazonCredentials.AWSRegion('us-west-2', ['us-west-2a'])
    ]
    0 * lookup.findAccountId()
    creds.size() == 1
    creds[0].regions.size() == 2
  }

  def 'useAccountRegions=true uses first defaultRegion for shared listRegions lookup'() {
    given: 'flag is on; account regions are fully specified (AZs present) so no AWS call needed'
    def config = new CredentialsConfig(
      useAccountRegions: true,
      defaultRegions: [
        new Region(name: 'eu-west-1', availabilityZones: ['eu-west-1a', 'eu-west-1b']),
        new Region(name: 'eu-central-1', availabilityZones: ['eu-central-1a'])
      ]
    )
    def accountsConfig = new AccountsConfiguration(accounts: [
      new Account(name: 'eu-account', accountId: '222')
    ])
    AWSCredentialsProvider provider = Mock(AWSCredentialsProvider)
    AmazonClientProvider clientProvider = Mock(AmazonClientProvider)
    AmazonCredentialsParser<Account, NetflixAmazonCredentials> ci = new AmazonCredentialsParser<>(
      provider, clientProvider, NetflixAmazonCredentials.class, config, accountsConfig)

    when:
    List<NetflixAmazonCredentials> creds = ci.load(config)

    then: 'all region AZs are in config so no AWS calls are made at all'
    0 * clientProvider.getAmazonEC2(_, _)
    creds.size() == 1
    creds[0].regions.size() == 2
    creds[0].regions.find { it.name == 'eu-west-1' }.availabilityZones == ['eu-west-1a', 'eu-west-1b']
  }

  def 'useAccountRegions=true uses first account region for findAccountId when account has regions'() {
    given: 'flag is on; account has explicit regions; accountId must be auto-discovered'
    def config = new CredentialsConfig(
      useAccountRegions: true,
      defaultRegions: [new Region(name: 'us-east-1', availabilityZones: ['us-east-1a'])]
    )
    def accountsConfig = new AccountsConfiguration(accounts: [
      new Account(
        name: 'auto-id-account',
        // no accountId — triggers findAccountId()
        regions: [new Region(name: 'ap-southeast-1', availabilityZones: ['ap-southeast-1a'])]
      )
    ])
    AWSCredentialsProvider provider = Mock(AWSCredentialsProvider)
    AmazonClientProvider clientProvider = Mock(AmazonClientProvider)
    AmazonEC2 apEc2 = Mock(AmazonEC2)
    AmazonCredentialsParser<Account, NetflixAmazonCredentials> ci = new AmazonCredentialsParser<>(
      provider, clientProvider, NetflixAmazonCredentials.class, config, accountsConfig)

    when:
    List<NetflixAmazonCredentials> creds = ci.load(config)

    then: 'findAccountId uses ap-southeast-1 (account first region), not the host region'
    1 * clientProvider.getAmazonEC2(provider, 'ap-southeast-1') >> apEc2
    // Simulate AccessDenied — the code extracts account ID from the IAM ARN in the error message
    1 * apEc2.describeVpcs() >> {
      def ex = new com.amazonaws.AmazonServiceException('AccessDenied')
      ex.setErrorCode('AccessDenied')
      ex.setErrorMessage('User: arn:aws:iam::333333333333:user/test is not authorized to perform: ec2:DescribeVpcs')
      throw ex
    }
    0 * clientProvider.getAmazonEC2(provider, 'us-east-1')
    creds.size() == 1
    creds[0].accountId == '333333333333'
  }

  def 'useAccountRegions=true falls back to shared lookup for findAccountId when account has no regions'() {
    given: 'flag is on but account has no per-account regions — must fall back to shared lookup'
    def config = new CredentialsConfig(
      useAccountRegions: true,
      // defaultRegions with no AZs so a listRegions lookup is still triggered
      defaultRegions: [new Region(name: 'us-east-1')]
    )
    def accountsConfig = new AccountsConfiguration(accounts: [
      new Account(name: 'no-region-account') // no accountId, no regions
    ])
    AWSCredentialsProvider provider = Mock(AWSCredentialsProvider)
    AWSAccountInfoLookup lookup = Mock(AWSAccountInfoLookup)
    AmazonCredentialsParser<Account, NetflixAmazonCredentials> ci = new AmazonCredentialsParser<>(
      provider, lookup, NetflixAmazonCredentials.class, config, accountsConfig)

    when:
    List<NetflixAmazonCredentials> creds = ci.load(config)

    then: 'shared lookup is used for both findAccountId and listRegions'
    1 * lookup.findAccountId() >> '444'
    1 * lookup.listRegions(['us-east-1']) >> [new AmazonCredentials.AWSRegion('us-east-1', ['us-east-1a'])]
    creds.size() == 1
    creds[0].accountId == '444'
  }
}
