package com.linagora.tmail.james.jmap.method

import com.linagora.tmail.james.jmap.json.{TeamMailboxMemberSerializer => Serializer}
import com.linagora.tmail.james.jmap.method.CapabilityIdentifier.LINAGORA_TEAM_MAILBOXES
import com.linagora.tmail.james.jmap.model.{TMBParserFailureEntry, TMBParserResultEntry, TMBParserSuccessEntry, TeamMailboxMemberSetRequest, TeamMailboxMemberSetResponse, TeamMailboxMemberSetResult, TeamMailboxNameDTO}
import com.linagora.tmail.team.TeamMemberRole.ManagerRole
import com.linagora.tmail.team.{TeamMailbox, TeamMailboxMember, TeamMailboxRepository}
import eu.timepit.refined.auto._
import jakarta.inject.Inject
import org.apache.james.core.Username
import org.apache.james.jmap.core.CapabilityIdentifier.{CapabilityIdentifier, JMAP_CORE, JMAP_MAIL}
import org.apache.james.jmap.core.Invocation.{Arguments, MethodName}
import org.apache.james.jmap.core.SetError.SetErrorDescription
import org.apache.james.jmap.core.{Invocation, SessionTranslator}
import org.apache.james.jmap.json.ResponseSerializer
import org.apache.james.jmap.method.{InvocationWithContext, MethodRequiringAccountId}
import org.apache.james.jmap.routes.SessionSupplier
import org.apache.james.mailbox.MailboxSession
import org.apache.james.metrics.api.MetricFactory
import org.reactivestreams.Publisher
import play.api.libs.json.JsObject
import reactor.core.scala.publisher.{SFlux, SMono}

class TeamMailboxMemberSetMethod @Inject()(val teamMailboxRepository: TeamMailboxRepository,
                                           val metricFactory: MetricFactory,
                                           val sessionTranslator: SessionTranslator,
                                           val sessionSupplier: SessionSupplier) extends MethodRequiringAccountId[TeamMailboxMemberSetRequest] {

  override val methodName: Invocation.MethodName = MethodName("TeamMailboxMember/set")

  override val requiredCapabilities: Set[CapabilityIdentifier] = Set(JMAP_CORE, JMAP_MAIL, LINAGORA_TEAM_MAILBOXES)

  override def getRequest(mailboxSession: MailboxSession, invocation: Invocation): Either[Exception, TeamMailboxMemberSetRequest] =
    Serializer.deserializeSetRequest(invocation.arguments.value)
      .asEither.left.map(ResponseSerializer.asException)

  override def doProcess(capabilities: Set[CapabilityIdentifier],
                         invocation: InvocationWithContext,
                         mailboxSession: MailboxSession,
                         request: TeamMailboxMemberSetRequest): Publisher[InvocationWithContext] =
    updatePerform(mailboxSession.getUser, request)
      .map(response => Invocation(
        methodName = methodName,
        arguments = Arguments(Serializer.serializeSetResponse(response).as[JsObject]),
        methodCallId = invocation.invocation.methodCallId))
      .map(InvocationWithContext(_, invocation.processingContext))

  private def updatePerform(username: Username, request: TeamMailboxMemberSetRequest): SMono[TeamMailboxMemberSetResponse] =
    SFlux.fromIterable(request.validatedUpdateRequest())
      .flatMap(updateRequestEntry => updateParserResultEntry(username, updateRequestEntry))
      .collectSeq()
      .map(listResult => TeamMailboxMemberSetResponse.from(request.accountId, listResult))

  private def updateParserResultEntry(requestUsername: Username,
                                      parserResultEntry: TMBParserResultEntry): SMono[TeamMailboxMemberSetResult] =
    parserResultEntry match {
      case TMBParserFailureEntry(tmbNameDto, exception) => SMono.just(TeamMailboxMemberSetResult.notUpdated(tmbNameDto, SetErrorDescription(exception.message)))
      case TMBParserSuccessEntry(teamMailbox, membersUpdateToAdd, membersUpdateToRemove) =>
        SFlux(teamMailboxRepository.listMembers(teamMailbox))
          .collectMap(member => member.username, member => member)
          .flatMap(presentMembers => presentMembers.get(requestUsername) match {
            case Some(member) if member.role.value.equals(ManagerRole) => updateParserSuccessEntry(teamMailbox, membersUpdateToAdd, membersUpdateToRemove, presentMembers)
            case None => SMono.just(TeamMailboxMemberSetResult.notUpdated(teamMailbox, SetErrorDescription("You are not a manager")))
          })
    }

  private def updateParserSuccessEntry(teamMailbox: TeamMailbox,
                                       membersUpdateToAdd: List[TeamMailboxMember],
                                       membersUpdateToRemove: Set[Username],
                                       presentMembers: Map[Username, TeamMailboxMember]): SMono[TeamMailboxMemberSetResult] =
    SFlux.fromIterable(membersUpdateToRemove)
      .filter(username => presentMembers.contains(username))
      .next()
      .map(username => TeamMailboxMemberSetResult.notUpdated(teamMailbox, SetErrorDescription(s"Could not remove a manager: ${username.asString()}")))
      .switchIfEmpty(addAndRemoveMember(teamMailbox, membersUpdateToAdd, membersUpdateToRemove))

  private def addAndRemoveMember(teamMailbox: TeamMailbox,
                                 membersUpdateToAdd: List[TeamMailboxMember],
                                 membersUpdateToRemove: Set[Username]): SMono[TeamMailboxMemberSetResult] =
    removeMember(teamMailbox, membersUpdateToRemove)
      .`then`(addMember(teamMailbox, membersUpdateToAdd))
      .`then`(SMono.just(TeamMailboxMemberSetResult(Option[TeamMailboxNameDTO](TeamMailboxNameDTO(teamMailbox.asString())), Option.empty)))

  private def addMember(teamMailbox: TeamMailbox, members: List[TeamMailboxMember]): SMono[Unit] =
    SFlux.fromIterable(members)
      .flatMap(member => teamMailboxRepository.addMember(teamMailbox, member))
      .`then`()
  private def removeMember(teamMailbox: TeamMailbox, usernames: Set[Username]): SMono[Unit] =
    SFlux.fromIterable(usernames)
      .flatMap(username => teamMailboxRepository.removeMember(teamMailbox, username))
      .`then`()
}