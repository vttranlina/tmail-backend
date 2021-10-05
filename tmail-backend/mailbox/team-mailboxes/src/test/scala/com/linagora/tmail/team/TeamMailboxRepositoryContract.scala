package com.linagora.tmail.team

import com.linagora.tmail.team.TeamMailboxRepositoryContract.{ANDRE, BOB, TEAM_MAILBOX, TEAM_MAILBOX_2, TEAM_MAILBOX_USER, TEAM_MAILBOX_USERNAME, TEAM_MAILBOX_USERNAME_2, TEAM_MAILBOX_USER_2}
import eu.timepit.refined.auto._
import org.apache.james.core.{Domain, Username}
import org.apache.james.mailbox.inmemory.InMemoryMailboxManager
import org.apache.james.mailbox.inmemory.manager.InMemoryIntegrationResources
import org.apache.james.mailbox.model.MailboxACL
import org.apache.james.mailbox.{MailboxManager, MailboxSession, SessionProvider}
import org.assertj.core.api.Assertions.{assertThat, assertThatThrownBy}
import org.junit.jupiter.api.{BeforeEach, Test}
import reactor.core.scala.publisher.{SFlux, SMono}

import java.util
import scala.jdk.CollectionConverters._

object TeamMailboxRepositoryContract {
  val TEAM_MAILBOX_USER: Domain = Domain.of("linagora.com")
  val TEAM_MAILBOX_USER_2: Domain = Domain.of("linagora2.com")
  val TEAM_MAILBOX_USERNAME: Username = Username.of(TEAM_MAILBOX_USER.asString())
  val TEAM_MAILBOX_USERNAME_2: Username = Username.of(TEAM_MAILBOX_USER_2.asString())
  val TEAM_MAILBOX: TeamMailbox = TeamMailbox(TEAM_MAILBOX_USER, TeamMailboxName("marketing"))
  val TEAM_MAILBOX_2: TeamMailbox = TeamMailbox(TEAM_MAILBOX_USER_2, TeamMailboxName("sale"))
  val BOB: Username = Username.of("bob")
  val ANDRE: Username = Username.of("andre")
}

trait TeamMailboxRepositoryContract {

  def testee: TeamMailboxRepository

  def mailboxManager: MailboxManager

  def sessionProvider: SessionProvider

  @Test
  def createTeamMailboxShouldStoreThreeAssignMailboxes(): Unit = {
    SMono.fromPublisher(testee.createTeamMailbox(TEAM_MAILBOX)).block()
    val session: MailboxSession = sessionProvider.createSystemSession(TEAM_MAILBOX_USERNAME)
    assertThat(SMono.fromPublisher(mailboxManager.mailboxExists(TEAM_MAILBOX.mailboxPath, session))
      .block())
      .isTrue
    assertThat(SMono.fromPublisher(mailboxManager.mailboxExists(TEAM_MAILBOX.inboxPath, session))
      .block())
      .isTrue
    assertThat(SMono.fromPublisher(mailboxManager.mailboxExists(TEAM_MAILBOX.sentPath, session))
      .block())
      .isTrue
  }

  @Test
  def createTeamMailboxShouldThrowWhenAssignMailboxExists(): Unit = {
    SMono.fromPublisher(testee.createTeamMailbox(TEAM_MAILBOX)).block()
    assertThatThrownBy(() => SMono.fromPublisher(testee.createTeamMailbox(TEAM_MAILBOX)).block())
      .hasMessageContaining(s"${TEAM_MAILBOX.mailboxPath.toString} already exists.")
  }

  @Test
  def createMailboxWithSamePathShouldFailWhenTeamMailboxExists(): Unit = {
    SMono.fromPublisher(testee.createTeamMailbox(TEAM_MAILBOX)).block()
    val session: MailboxSession = sessionProvider.createSystemSession(TEAM_MAILBOX_USERNAME)
    assertThatThrownBy(() => mailboxManager.createMailbox(TEAM_MAILBOX.mailboxPath, session))
      .hasMessageContaining(s"${TEAM_MAILBOX.mailboxPath.toString} already exists.")

    assertThatThrownBy(() => mailboxManager.createMailbox(TEAM_MAILBOX.inboxPath, session))
      .hasMessageContaining(s"${TEAM_MAILBOX.inboxPath.toString} already exists.")

    assertThatThrownBy(() => mailboxManager.createMailbox(TEAM_MAILBOX.sentPath, session))
      .hasMessageContaining(s"${TEAM_MAILBOX.sentPath.toString} already exists.")
  }

  @Test
  def deleteTeamMailboxShouldRemoveAllAssignMailbox(): Unit = {
    SMono.fromPublisher(testee.createTeamMailbox(TEAM_MAILBOX)).block()
    SMono.fromPublisher(testee.deleteTeamMailbox(TEAM_MAILBOX)).block()
    val session: MailboxSession = sessionProvider.createSystemSession(TEAM_MAILBOX_USERNAME)
    assertThat(SMono.fromPublisher(mailboxManager.mailboxExists(TEAM_MAILBOX.mailboxPath, session))
      .block())
      .isFalse
    assertThat(SMono.fromPublisher(mailboxManager.mailboxExists(TEAM_MAILBOX.inboxPath, session))
      .block())
      .isFalse
    assertThat(SMono.fromPublisher(mailboxManager.mailboxExists(TEAM_MAILBOX.sentPath, session))
      .block())
      .isFalse
  }

  @Test
  def deleteTeamMailboxShouldNotRemoveUnAssignMailbox(): Unit = {
    SMono.fromPublisher(testee.createTeamMailbox(TEAM_MAILBOX)).block()
    SMono.fromPublisher(testee.createTeamMailbox(TEAM_MAILBOX_2)).block()
    SMono.fromPublisher(testee.deleteTeamMailbox(TEAM_MAILBOX)).block()
    val session: MailboxSession = sessionProvider.createSystemSession(TEAM_MAILBOX_USERNAME_2)
    assertThat(SMono.fromPublisher(mailboxManager.mailboxExists(TEAM_MAILBOX_2.mailboxPath, session))
      .block())
      .isTrue
    assertThat(SMono.fromPublisher(mailboxManager.mailboxExists(TEAM_MAILBOX_2.inboxPath, session))
      .block())
      .isTrue
    assertThat(SMono.fromPublisher(mailboxManager.mailboxExists(TEAM_MAILBOX_2.sentPath, session))
      .block())
      .isTrue
  }

  @Test
  def deleteTeamMailboxShouldThrowWhenAssignMailboxDoesNotExists(): Unit = {
    assertThatThrownBy(() => SMono.fromPublisher(testee.deleteTeamMailbox(TEAM_MAILBOX)).block())
      .hasMessageContaining(s"${TEAM_MAILBOX.mailboxPath.toString} can not be found")
  }

  @Test
  def addMemberShouldThrowWhenTeamMailboxDoesNotExists(): Unit = {
    assertThatThrownBy(() => SMono.fromPublisher(testee.addMember(TEAM_MAILBOX, BOB)).block())
      .isInstanceOf(classOf[TeamMailboxNotFoundException])
  }

  @Test
  def addMemberShouldAddImplicitRights(): Unit = {
    SMono.fromPublisher(testee.createTeamMailbox(TEAM_MAILBOX)).block()
    SMono.fromPublisher(testee.addMember(TEAM_MAILBOX, BOB)).block()
    val bobSession: MailboxSession = sessionProvider.createSystemSession(BOB)
    val entriesRights: util.Map[MailboxACL.EntryKey, MailboxACL.Rfc4314Rights] = mailboxManager.listRights(TEAM_MAILBOX.mailboxPath, bobSession).getEntries

    assertThat(entriesRights)
      .hasSize(1)
    assertThat(entriesRights.asScala.head._2.toString)
      .isEqualTo("lprstw")
  }

  @Test
  def removeMemberShouldThrowWhenTeamMailboxDoesNotExists(): Unit = {
    assertThatThrownBy(() => SMono.fromPublisher(testee.removeMember(TEAM_MAILBOX, BOB)).block())
      .isInstanceOf(classOf[TeamMailboxNotFoundException])
  }

  @Test
  def removeMemberShouldThrowWhenAssignMemberNotInTeamMailbox(): Unit = {
    SMono.fromPublisher(testee.createTeamMailbox(TEAM_MAILBOX)).block()
    assertThatThrownBy(() => SMono.fromPublisher(testee.removeMember(TEAM_MAILBOX, BOB)).block())
      .isInstanceOf(classOf[TeamMailboxNotFoundException])
  }

  @Test
  def removeMemberShouldRevokeAssignMemberFromTeamMailbox(): Unit = {
    SMono.fromPublisher(testee.createTeamMailbox(TEAM_MAILBOX)).block()
    SMono.fromPublisher(testee.addMember(TEAM_MAILBOX, BOB)).block()
    SMono.fromPublisher(testee.removeMember(TEAM_MAILBOX, BOB)).block()

    assertThat(SFlux.fromPublisher(testee.listMembers(TEAM_MAILBOX)).collectSeq().block().asJava)
      .doesNotContain(BOB)
  }

  @Test
  def removeMemberShouldNotRevokeUnAssignMember(): Unit = {
    SMono.fromPublisher(testee.createTeamMailbox(TEAM_MAILBOX)).block()
    SMono.fromPublisher(testee.addMember(TEAM_MAILBOX, BOB)).block()
    SMono.fromPublisher(testee.addMember(TEAM_MAILBOX, ANDRE)).block()
    SMono.fromPublisher(testee.removeMember(TEAM_MAILBOX, BOB)).block()

    assertThat(SFlux.fromPublisher(testee.listMembers(TEAM_MAILBOX)).collectSeq().block().asJava)
      .contains(ANDRE)
  }

  @Test
  def listMemberShouldReturnEmptyWhenDefault(): Unit = {
    SMono.fromPublisher(testee.createTeamMailbox(TEAM_MAILBOX)).block()
    assertThat(SFlux.fromPublisher(testee.listMembers(TEAM_MAILBOX)).collectSeq().block().asJava)
      .isEmpty()
  }

  @Test
  def listMemberShouldReturnListMembersHaveRightsWhenSingleMember(): Unit = {
    SMono.fromPublisher(testee.createTeamMailbox(TEAM_MAILBOX)).block()
    SMono.fromPublisher(testee.addMember(TEAM_MAILBOX, BOB)).block()

    assertThat(SFlux.fromPublisher(testee.listMembers(TEAM_MAILBOX)).collectSeq().block().asJava)
      .containsExactlyInAnyOrder(BOB)
  }

  @Test
  def listMemberShouldReturnListMembersHaveRightsWhenSeveralMembers(): Unit = {
    SMono.fromPublisher(testee.createTeamMailbox(TEAM_MAILBOX)).block()
    SMono.fromPublisher(testee.addMember(TEAM_MAILBOX, BOB)).block()
    SMono.fromPublisher(testee.addMember(TEAM_MAILBOX, ANDRE)).block()

    assertThat(SFlux.fromPublisher(testee.listMembers(TEAM_MAILBOX)).collectSeq().block().asJava)
      .containsExactlyInAnyOrder(BOB, ANDRE)
  }

  @Test
  def listTeamMailboxesByUserShouldReturnEmptyByDefault(): Unit = {
    assertThat(SFlux.fromPublisher(testee.listTeamMailboxes(BOB)).collectSeq().block().asJava)
      .isEmpty()
  }

  @Test
  def listTeamMailboxesByUserShouldReturnTeamMailboxesWhichAssignUserInThat(): Unit = {
    SMono.fromPublisher(testee.createTeamMailbox(TEAM_MAILBOX)).block()
    SMono.fromPublisher(testee.addMember(TEAM_MAILBOX, BOB)).block()

    assertThat(SFlux.fromPublisher(testee.listTeamMailboxes(BOB)).collectSeq().block().asJava)
      .containsExactlyInAnyOrder(TEAM_MAILBOX)
  }

  @Test
  def listTeamMailboxesByUserShouldNotReturnTeamMailboxesWhichAssignUserNotInThat(): Unit = {
    SMono.fromPublisher(testee.createTeamMailbox(TEAM_MAILBOX)).block()
    SMono.fromPublisher(testee.addMember(TEAM_MAILBOX, ANDRE)).block()

    assertThat(SFlux.fromPublisher(testee.listTeamMailboxes(BOB)).collectSeq().block().asJava)
      .isEmpty()
  }

  @Test
  def userCanHaveSeveralTeamMailboxes(): Unit = {
    SMono.fromPublisher(testee.createTeamMailbox(TEAM_MAILBOX)).block()
    SMono.fromPublisher(testee.addMember(TEAM_MAILBOX, BOB)).block()
    SMono.fromPublisher(testee.createTeamMailbox(TEAM_MAILBOX_2)).block()
    SMono.fromPublisher(testee.addMember(TEAM_MAILBOX_2, BOB)).block()

    assertThat(SFlux.fromPublisher(testee.listTeamMailboxes(BOB)).collectSeq().block().asJava)
      .containsExactlyInAnyOrder(TEAM_MAILBOX, TEAM_MAILBOX_2)
  }

  @Test
  def listTeamMailboxesByDomainShouldReturnEmptyByDefault(): Unit = {
    assertThat(SFlux.fromPublisher(testee.listTeamMailboxes(TEAM_MAILBOX_USER)).collectSeq().block().asJava)
      .isEmpty()
  }

  @Test
  def listTeamMailboxesByDomainShouldReturnStoredEntriesWhenSingle(): Unit = {
    val saleTeam: TeamMailbox = TeamMailbox(TEAM_MAILBOX_USER, TeamMailboxName("sale"))
    SMono.fromPublisher(testee.createTeamMailbox(saleTeam)).block()
    assertThat(SFlux.fromPublisher(testee.listTeamMailboxes(TEAM_MAILBOX_USER)).collectSeq().block().asJava)
      .containsExactlyInAnyOrder(saleTeam)
  }

  @Test
  def listTeamMailboxesByDomainShouldReturnStoredEntriesWhenMultiple(): Unit = {
    val saleTeam: TeamMailbox = TeamMailbox(TEAM_MAILBOX_USER, TeamMailboxName("sale"))
    val marketingTeam: TeamMailbox = TeamMailbox(TEAM_MAILBOX_USER, TeamMailboxName("marketing"))
    SMono.fromPublisher(testee.createTeamMailbox(saleTeam)).block()
    SMono.fromPublisher(testee.createTeamMailbox(marketingTeam)).block()
    assertThat(SFlux.fromPublisher(testee.listTeamMailboxes(TEAM_MAILBOX_USER)).collectSeq().block().asJava)
      .containsExactlyInAnyOrder(saleTeam, marketingTeam)
  }

  @Test
  def listTeamMailboxesByDomainShouldNotReturnUnRelatedEntries(): Unit = {
    val saleTeam: TeamMailbox = TeamMailbox(TEAM_MAILBOX_USER, TeamMailboxName("sale"))
    val marketingTeam: TeamMailbox = TeamMailbox(TEAM_MAILBOX_USER_2, TeamMailboxName("marketing"))
    SMono.fromPublisher(testee.createTeamMailbox(saleTeam)).block()
    SMono.fromPublisher(testee.createTeamMailbox(marketingTeam)).block()

    assertThat(SFlux.fromPublisher(testee.listTeamMailboxes(TEAM_MAILBOX_USER)).collectSeq().block().asJava)
      .containsExactlyInAnyOrder(saleTeam)
  }

}

class TeamMailboxRepositoryTest extends TeamMailboxRepositoryContract {
  override def testee: TeamMailboxRepository = teamMailboxRepositoryImpl

  override def mailboxManager: MailboxManager = inMemoryMailboxManager

  override def sessionProvider: SessionProvider = inMemorySessionProvider

  var teamMailboxRepositoryImpl: TeamMailboxRepositoryImpl = _
  var inMemoryMailboxManager: InMemoryMailboxManager = _
  var inMemorySessionProvider: SessionProvider = _

  @BeforeEach
  def setUp(): Unit = {
    val resource: InMemoryIntegrationResources = InMemoryIntegrationResources.defaultResources()
    inMemoryMailboxManager = resource.getMailboxManager
    inMemorySessionProvider = inMemoryMailboxManager.getSessionProvider
    teamMailboxRepositoryImpl = new TeamMailboxRepositoryImpl(inMemoryMailboxManager, inMemorySessionProvider)
  }
}