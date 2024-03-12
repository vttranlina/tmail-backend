package com.linagora.tmail.james.jmap.method

import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.format.DateTimeFormatter
import java.util.{Locale, UUID}

import com.linagora.tmail.james.jmap.method.CalendarEventReplyPerformer.{I18N_MAIL_TEMPLATE_LOCATION_PROPERTY, LANGUAGE_DEFAULT}
import com.linagora.tmail.james.jmap.model.{AttendeeReply, CalendarEventNotParsable, CalendarEventParsed, CalendarEventReplyGenerator, CalendarEventReplyRequest, CalendarEventReplyResults, CalendarOrganizerField, CalendarStartField, CalendarTitleField, InvalidCalendarFileException}
import eu.timepit.refined.auto._
import jakarta.mail.BodyPart
import javax.annotation.PreDestroy
import javax.inject.Inject
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.parameter.PartStat
import org.apache.commons.io.IOUtils
import org.apache.james.core.MailAddress
import org.apache.james.core.builder.MimeMessageBuilder
import org.apache.james.core.builder.MimeMessageBuilder.BodyPartBuilder
import org.apache.james.filesystem.api.FileSystem
import org.apache.james.jmap.mail.{BlobId, BlobIds}
import org.apache.james.jmap.method.EmailSubmissionSetMethod.LOGGER
import org.apache.james.jmap.routes.{BlobNotFoundException, BlobResolvers}
import org.apache.james.lifecycle.api.{LifecycleUtil, Startable}
import org.apache.james.mailbox.MailboxSession
import org.apache.james.queue.api.MailQueueFactory.SPOOL
import org.apache.james.queue.api.{MailQueue, MailQueueFactory}
import org.apache.james.server.core.{MailImpl, MissingArgumentException}
import org.apache.james.util.MimeMessageUtil
import org.apache.james.utils.PropertiesProvider
import org.apache.mailet.Mail
import reactor.core.scala.publisher.{SFlux, SMono}
import reactor.core.scheduler.Schedulers

import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.util.{Failure, Success, Try, Using}


object CalendarEventReplyPerformer {
  val LANGUAGE_DEFAULT: Locale = Locale.ENGLISH
  val I18N_MAIL_TEMPLATE_LOCATION_PROPERTY : String = "jmap.calendar_event.reply.i18n.location"
}

class CalendarEventReplyPerformer @Inject()(blobCalendarResolver: BlobCalendarResolver,
                                            mailQueueFactory: MailQueueFactory[_ <: MailQueue],
                                            fileSystem: FileSystem,
                                            propertiesProvider: PropertiesProvider) extends Startable {

  private val mailReplyGenerator: CalendarEventMailReplyGenerator = Try(propertiesProvider.getConfiguration("jmap"))
    .map(configuration => configuration.getString(I18N_MAIL_TEMPLATE_LOCATION_PROPERTY))
    .map(i18nEmlDirectory => new I18NMailCalendarEventReplyContent(fileSystem, i18nEmlDirectory)) match {
    case Success(value) => new CalendarEventMailReplyGenerator(value)
    case Failure(_) => throw new MissingArgumentException("JMAP CalendarEvent needs a " + I18N_MAIL_TEMPLATE_LOCATION_PROPERTY + " entry")
  }

  var queue: MailQueue = _

  def init: Unit =
    queue = mailQueueFactory.createQueue(SPOOL)

  @PreDestroy
  def dispose: Unit = Try(queue.close()).recover(e => LOGGER.debug("error closing queue", e))

  def process(request: CalendarEventReplyRequest, mailboxSession: MailboxSession, partStat: PartStat): SMono[CalendarEventReplyResults] = {
    val attendeeReply: AttendeeReply = AttendeeReply(mailboxSession.getUser, partStat)
    val language: Locale = getLanguageLocale(request)

    SMono.fromCallable(() => extractParsedBlobIds(request))
      .flatMapMany { case (notParsable: CalendarEventNotParsable, parsedBlobId: Seq[BlobId]) =>
        SFlux.fromIterable(parsedBlobId)
          .flatMap(blobId => generateReplyMailAndTryEnqueue(blobId, mailboxSession, attendeeReply, language))
          .mergeWith(SFlux.just(CalendarEventReplyResults.notDone(notParsable)))
      }
      .reduce(CalendarEventReplyResults.empty)(CalendarEventReplyResults.merge)
  }

  private def extractParsedBlobIds(request: CalendarEventReplyRequest): (CalendarEventNotParsable, Seq[BlobId]) =
    request.blobIds.value.foldLeft((CalendarEventNotParsable(Set.empty), Seq.empty[BlobId])) { (resultBuilder, unparsedBlobId) =>
      BlobId.of(unparsedBlobId) match {
        case Success(blobId) => (resultBuilder._1, resultBuilder._2 :+ blobId)
        case Failure(_) => (resultBuilder._1.merge(CalendarEventNotParsable(Set(unparsedBlobId))), resultBuilder._2)
      }
    }

  private def generateReplyMailAndTryEnqueue(blobId: BlobId, mailboxSession: MailboxSession, attendeeReply: AttendeeReply, language: Locale): SMono[CalendarEventReplyResults] =
    blobCalendarResolver.resolveRequestCalendar(blobId, mailboxSession)
      .flatMap(calendarRequest => mailReplyGenerator.generateMail(calendarRequest, attendeeReply, language))
      .flatMap(replyMail => SMono(queue.enqueueReactive(replyMail))
        .`then`(SMono.fromCallable(() => LifecycleUtil.dispose(replyMail))
          .subscribeOn(Schedulers.boundedElastic()))
        .`then`(SMono.just(CalendarEventReplyResults(done = BlobIds(Seq(blobId.value))))))
      .onErrorResume({
        case _: InvalidCalendarFileException => SMono.just(CalendarEventReplyResults.notDone(blobId))
        case _: BlobNotFoundException => SMono.just(CalendarEventReplyResults.notFound(blobId))
        case e => SMono.error(e)
      })

  private def getLanguageLocale(request: CalendarEventReplyRequest): Locale = request.language.map(_.language).getOrElse(LANGUAGE_DEFAULT)
}

class BlobCalendarResolver @Inject()(blobResolvers: BlobResolvers) {
  def resolveRequestCalendar(blobId: BlobId, mailboxSession: MailboxSession): SMono[Calendar] = {
    blobResolvers.resolve(blobId, mailboxSession)
      .flatMap(blob =>
        Using(blob.content)(CalendarEventParsed.parseICal4jCalendar).toEither
          .flatMap(calendar => validate(calendar))
          .fold(error => SMono.error[Calendar](InvalidCalendarFileException(blobId, error)), SMono.just))
  }

  private def validate(calendar: Calendar): Either[IllegalArgumentException, Calendar] =
    if (calendar.getComponents("VEVENT").isEmpty) {
      Left(new IllegalArgumentException("The calendar file must contain VEVENT componennt"))
    } else if (calendar.getMethod.getValue != "REQUEST") {
      Left(new IllegalArgumentException("The calendar must have REQUEST a s a method"))
    } else {
      Right(calendar)
    }
}

class CalendarEventMailReplyGenerator(val bodyPartContentGenerator: CalendarReplyBodyPartContentGenerator) {

  private val ICS_FILE_NAME: String = "invite.ics"

  def generateMail(calendarRequest: Calendar, attendeeReply: AttendeeReply, language: Locale): SMono[Mail] =
    extractRecipient(calendarRequest)
      .fold(e => SMono.error(e), recipient => generateAttachmentPart(calendarRequest, attendeeReply)
        .flatMap(attachmentPart => bodyPartContentGenerator.getBodyPartContent(language, attendeeReply, calendarRequest)
          .map(bodyPartContent => MimeMessageBuilder.mimeMessageBuilder
            .setMultipartWithBodyParts(Seq(bodyPartContent.bodyPart).concat(attachmentPart.map(_.build())): _*)
            .setSubject(bodyPartContent.subject)
            .build))
        .map(mimeMessage => MailImpl.builder()
          .name(generateMailName())
          .sender(attendeeReply.attendee.asString())
          .addRecipients(recipient)
          .mimeMessage(mimeMessage)
          .build()))

  private def generateAttachmentPart(calendarRequest: Calendar, attendeeReply: AttendeeReply): SMono[Seq[BodyPartBuilder]] =
    SMono.fromCallable(() => CalendarEventReplyGenerator.generate(calendarRequest, attendeeReply))
      .map(calendarReply => calendarReply.toString.getBytes(StandardCharsets.UTF_8))
      .map(calendarAsByte => Seq(MimeMessageBuilder.bodyPartBuilder
        .data(calendarAsByte)
        .addHeader("Content-Type", "text/calendar; charset=UTF-8"),
        MimeMessageBuilder.bodyPartBuilder
          .data(calendarAsByte)
          .filename(ICS_FILE_NAME)
          .disposition("attachment")
          .`type`("application/ics")))

  private def extractRecipient(calendar: Calendar): Either[Exception, MailAddress] =
    calendar.getComponents[VEvent]("VEVENT").asScala.headOption
      .flatMap(CalendarOrganizerField.from)
      .flatMap(_.mailto) match {
      case Some(value) => scala.Right(value)
      case None => scala.Left(new IllegalArgumentException("Cannot extract the organizer from the calendar event."))
    }

  private def generateMailName(): String = "calendar-reply-" + UUID.randomUUID().toString
}

case class ReplyBodyPart(subject: String, bodyPart: BodyPart)

trait CalendarReplyBodyPartContentGenerator {
  def getBodyPartContent(i18n: Locale, attendeeReply: AttendeeReply, calendar: Calendar): SMono[ReplyBodyPart]
}

class I18NMailCalendarEventReplyContent(fileSystem: FileSystem, i18nEmlDirectory: String) extends CalendarReplyBodyPartContentGenerator {

  val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE MMM dd, yyyy")
  private val ATTENDEE_TAG: String = "{{ATTENDEE}}"

  override def getBodyPartContent(i18n: Locale, attendeeReply: AttendeeReply, calendar: Calendar): SMono[ReplyBodyPart] = {
    val emlFilename: String = evaluateMailTemplateFileName(attendeeReply.partStat, i18n)
    val emlLocation: String = URI.create(i18nEmlDirectory).resolve(emlFilename).toString

    SMono.fromCallable(() => fileSystem.getResource(emlLocation))
      .map(IOUtils.toByteArray)
      .map(MimeMessageUtil.mimeMessageFromBytes)
      .map(mimeMessage => {
        val bodyPart = mimeMessage.getContent match {
          case textBody: String => MimeMessageBuilder.bodyPartBuilder.data(decorateEmailBody(textBody, attendeeReply)).build()
          case _ => throw new IllegalArgumentException("The eml file must contain a text body.")
        }
        ReplyBodyPart(generateSubject(attendeeReply, calendar, Option(mimeMessage.getSubject)), bodyPart)
      })
  }

  private def generateSubject(attendeeReply: AttendeeReply, calendar: Calendar, statPrefix: Option[String] = None): String = {
    val event: VEvent = calendar.getComponents[VEvent]("VEVENT").asScala.head
    val eventTitle: Option[CalendarTitleField] = CalendarTitleField.from(event)
    val startDate: Option[CalendarStartField] = CalendarStartField.from(event)
    val finalStatPrefix = statPrefix.getOrElse(attendeeReply.partStat.getValue)
    s"${finalStatPrefix} " + eventTitle.map(_.value).getOrElse("") + startDate.map(sd => " @ " + sd.value.format(dateTimeFormatter)).getOrElse("") + " (" + attendeeReply.attendee.asMailAddress().toString + ")"
  }

  private def evaluateMailTemplateFileName(partStat: PartStat, language: Locale): String =
    s"calendar_reply_${partStat.getValue.toLowerCase}-${language.toLanguageTag}.eml"

  private def decorateEmailBody(emlContent: String, attendeeReply: AttendeeReply): String =
    emlContent.replace(ATTENDEE_TAG, attendeeReply.attendee.asMailAddress().toString)
}
