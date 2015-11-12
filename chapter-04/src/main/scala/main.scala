import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import slick.driver.H2Driver.api._
import scala.util.Try

object Example extends App {

  // Row representation:
  final case class Message(sender: String, content: String, id: Long = 0L)

  // Schema:
  final class MessageTable(tag: Tag) extends Table[Message](tag, "message") {
    def id      = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def sender  = column[String]("sender")
    def content = column[String]("content")
    def * = (sender, content, id) <> (Message.tupled, Message.unapply)
  }

  // Table:
  lazy val messages = TableQuery[MessageTable]

  // Database connection details:
  val db = Database.forConfig("chapter04")

  // Helper method for running a query in this example file:
  def exec[T](program: DBIO[T]): T =
    Await.result(db.run(program), 5000 milliseconds)

  def testData = Seq(
    Message("Dave", "Hello, HAL. Do you read me, HAL?"),
    Message("HAL",  "Affirmative, Dave. I read you."),
    Message("Dave", "Open the pod bay doors, HAL."),
    Message("HAL",  "I'm sorry, Dave. I'm afraid I can't do that."))

  def populate =
    DBIO.seq(
      messages.schema.create,
      messages ++= testData
    )

  // Utility to print out what is in the database:
  def printCurrentDatabaseState() = {
    println("\nState of the database:")
    exec(messages.result.map(_.foreach(println)))
  }

  try {
    exec(populate)

    // Map:
    val textAction: DBIO[Option[String]] =
      messages.map(_.content).result.headOption

    val encrypted: DBIO[Option[String]] =
      textAction.map(maybeText => maybeText.map(_.reverse))

    println("\nAn 'encrypted' message from the database:")
    println(exec(encrypted))

    // FlatMap:
    val delete: DBIO[Int] = messages.delete
    def insert(count: Int) = messages += Message("NOBODY", s"I removed ${count} messages")

    val resetMessagesAction: DBIO[Int] = delete.flatMap{ count => insert(count) }

    val resetMessagesAction2: DBIO[Int] =
      delete.flatMap{
        case 0 | 1 => DBIO.successful(0)
        case n     => insert(n)
      }


    // Fold:

    // Feel free to implement a more realistic measure!
    def sentiment(m: Message): Int = scala.util.Random.nextInt(100)
    def isHappy(message: Message): Boolean = sentiment(message) > 50

    def sayingsOf(crewName: String): DBIO[Seq[Message]] =
      messages.filter(_.sender === crewName).result

    val actions: List[DBIO[Seq[Message]]] =
      sayingsOf("Dave") :: sayingsOf("HAL") :: Nil

    val roseTinted: DBIO[Seq[Message]] =
      DBIO.fold(actions, Seq.empty) {
        (happy, crewMessages) => crewMessages.filter(isHappy) ++ happy
      }

    println("\nHappy messages from fold:")
    println(exec(roseTinted))

    // Zip
    val countAndHal: DBIO[(Int, Seq[Message])] =
      messages.size.result zip messages.filter(_.sender === "HAL 9000").result
    println("\nZipped actions:")
    println(exec(countAndHal))

    //
    // Transactions
    //

    val willRollback = (
      (messages += Message("HAL",  "Daisy, Daisy..."))                   >>
      (messages += Message("Dave", "Please, anything but your singing")) >>
       DBIO.failed(new Exception("agggh my ears"))                       >>
      (messages += Message("HAL", "Give me your answer do"))
      ).transactionally

    println("\nResult from rolling back:")
    println(exec(willRollback.asTry))
    printCurrentDatabaseState

  } finally db.close

}