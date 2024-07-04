package com.linagora.tmail.james.jmap.model

import cats.implicits._
import com.linagora.tmail.team.{TeamMailbox, TeamMailboxMember}
import org.apache.james.core.Username
import org.apache.james.jmap.core.SetError.SetErrorDescription
import org.apache.james.jmap.core.{AccountId, SetError}
import org.apache.james.jmap.method.WithAccountId

import scala.util.Try

trait TMBParseException extends RuntimeException {
  def message: String

  override def getMessage: String = message
}
case class InvalidTeamMailboxException(teamMailboxNameDTO: TeamMailboxNameDTO) extends TMBParseException {
  override def message: String = s"Invalid team mailbox: ${teamMailboxNameDTO.value}"
}

case class InvalidRoleException(role: TeamMailboxMemberRoleDTO) extends TMBParseException {
  override def message: String = s"Invalid role: ${role.role}"
}

case class InvalidTeamMemberNameException(memberName: TeamMailboxMemberName) extends TMBParseException {
  override def message: String = s"Invalid team member name: ${memberName.value}"
}

trait TMBParserResultEntry
case class TMBParserSuccessEntry(teamMailbox: TeamMailbox,
                                 membersUpdateToAdd: List[TeamMailboxMember],
                                 membersUpdateToRemove: Set[Username]) extends TMBParserResultEntry
case class TMBParserFailureEntry(tmbNameDto: TeamMailboxNameDTO, exception: TMBParseException) extends TMBParserResultEntry

case class TeamMailboxMemberSetRequest(accountId: AccountId,
                                       update: Map[TeamMailboxNameDTO, Map[TeamMailboxMemberName, Option[TeamMailboxMemberRoleDTO]]]) extends WithAccountId {
  def validatedUpdateRequest(): List[TMBParserResultEntry] =
    update.map {
      case (teamMailboxNameDTO, membersUpdate) =>
        teamMailboxNameDTO.validate
          .flatMap { teamMailbox =>
            validateMemberName(membersUpdate).flatMap { validatedMembers =>
              for {
                addMembers <- getAddMembers(validatedMembers)
                removeMembers = getRemoveMembers(validatedMembers)
              } yield TMBParserSuccessEntry(teamMailbox, addMembers, removeMembers)
            }
          }.fold(e => TMBParserFailureEntry(teamMailboxNameDTO, e), identity)
    }.toList

  // todo validate, can not add and remove same member in single request (per teamMailbox)
  private def validateMemberName(map: Map[TeamMailboxMemberName, Option[TeamMailboxMemberRoleDTO]]): Either[TMBParseException, Map[Username, Option[TeamMailboxMemberRoleDTO]]] =
    map.toList.traverse {
      case (memberName, role) => memberName.validate.map(_ -> role)
    }.map(_.toMap)

  private def getAddMembers(map: Map[Username, Option[TeamMailboxMemberRoleDTO]]): Either[TMBParseException, List[TeamMailboxMember]] =
    map.collect {
      case (memberName, Some(role)) => role.validate.map(TeamMailboxMember(memberName, _))
    }.toList.sequence

  private def getRemoveMembers(map: Map[Username, Option[TeamMailboxMemberRoleDTO]]): Set[Username] =
    map.collect {
      case (memberName, None) => memberName
    }.toSet
}

object TeamMailboxMemberSetResponse {
  def from(accountId: AccountId, list: Seq[TeamMailboxMemberSetResult]): TeamMailboxMemberSetResponse = ???
}
case class TeamMailboxMemberSetResponse(accountId: AccountId,
                                        updated: Map[TeamMailboxNameDTO, String],
                                        notUpdated: Map[TeamMailboxNameDTO, SetError])

case class TeamMailboxNameDTO(value: String) {
  def validate: Either[InvalidTeamMailboxException, TeamMailbox] = TeamMailbox.fromString(value)
    .left.map(_ => InvalidTeamMailboxException(this))
}

case class TeamMailboxMemberName(value: String)  {
  def validate: Either[InvalidTeamMemberNameException, Username] = Try(Username.of(value))
    .toEither.left.map(_ =>  InvalidTeamMemberNameException(this))
}

case class TeamMailboxMemberSetFailure(teamMailboxName: TeamMailboxNameDTO,
                                       error: SetError)
object TeamMailboxMemberSetResult {
  def notUpdated(teamMailboxNameDTO: TeamMailboxNameDTO, setErrorDescription: SetErrorDescription): TeamMailboxMemberSetResult = ???
  def notUpdated(teamMailboxName: TeamMailbox, setErrorDescription: SetErrorDescription): TeamMailboxMemberSetResult =
    ???
  def notUpdated(teamMailboxName: TeamMailbox, error: SetError): TeamMailboxMemberSetResult =
    TeamMailboxMemberSetResult(notUpdated = Some(TeamMailboxMemberSetFailure(???, error)))

  def updated(teamMailboxName: TeamMailboxNameDTO): TeamMailboxMemberSetResult =
    TeamMailboxMemberSetResult(updated = Some(teamMailboxName))
}
case class TeamMailboxMemberSetResult(updated: Option[TeamMailboxNameDTO] = None,
                                      notUpdated: Option[TeamMailboxMemberSetFailure] = None)