package starling.props


import java.net.InetAddress
import starling.props.PropsHelper._
import starling.LIMServer
import java.io.File

class Props(props:Map[String,String]) extends PropsHelper(props) {
  object ServerName extends ServerNameStringProperty()
  object ServerType extends EnumProperty("Dev", "FC2", "Oil")
  object ServerNameOrBlank extends StringProperty(ServerName())
  object ServerColour extends StringProperty(PropsHelper.createColourString(ServerName()))
  object UseProductionColour extends BooleanProperty(false)

  object Production extends BooleanProperty(false)

  object ReadonlyMode extends BooleanProperty(false)
  object VarReportEmailFrom extends StringProperty("var-reports-test@trafigura.com")

  object FreightEmailAddress extends EmailProperty
  object MetalsEmailAddress extends EmailProperty
  object OilEmailAddress extends EmailProperty
  object AzuriteEmailAddress extends EmailProperty
  object GalenaEmailAddress extends EmailProperty
  object MalachiteEmailAddress extends EmailProperty
  object GMFEmailAddress extends EmailProperty
  object GSAEmailAddress extends EmailProperty
  object WuXiEmailAddress extends StringProperty("stacy.curl@trafigura.com")
  object LimEmailAddress extends StringProperty("stacy.curl@trafigura.com")

  object EnabledDesks extends StringProperty("")
  object EnableVerificationEmails extends BooleanProperty(true)

  object SmtpServerHost extends StringProperty("londonsmtp.mail.trafigura.com")
  object SmtpServerPort extends IntProperty(25)
  object HttpPort extends LocalPort(1024 + ((ServerName().hashCode.abs % 6400) * 10) + 0)
  object RmiPort extends LocalPort(1024 + ((ServerName().hashCode.abs % 6400) * 10) + 1)
  object XLLoopPort extends LocalPort(1024 + ((ServerName().hashCode.abs % 6400) * 10) + 2)
  object JmxPort extends LocalPort(1024 + ((ServerName().hashCode.abs % 6400) * 10) + 3) //used by start.sh
  object RegressionPort extends LocalPort(1024 + ((ServerName().hashCode.abs % 6400) * 10) + 4)
  object LoopyXLPort extends LocalPort(1024 + ((ServerName().hashCode.abs % 6400) * 10) + 5)

  object ExternalHostname extends StringProperty(InetAddress.getLocalHost().getHostName)
  object ExternalUrl extends StringProperty("http://" + ExternalHostname() + ":" + HttpPort())
  object XLLoopUrl extends StringProperty(ExternalHostname() + ":" + XLLoopPort())

  object RabbitHost extends StringProperty("")
  def rabbitHostSet = RabbitHost() != ""

  object StarlingDatabase extends DatabaseProperty("jdbc:jtds:sqlserver://TTRAFLOCOSQL08.global.trafigura.com/starling_test;instance=DB08", "starling", "ng1lr4ts123!Y^%&$")
  object FCASqlServer extends DatabaseProperty("jdbc:jtds:sqlserver://forwardcurves.dev.sql.trafigura.com:6321;databaseName=ForwardCurves", "curveUser", "jha12wsii")
//  object FCASqlServer extends DatabaseProperty("jdbc:jtds:sqlserver://TTRAFLONSQL12.global.trafigura.com/ForwardCurves;instance=DB12", "starling", "ng1lr4ts123!Y^%&$")
//  object FCASqlServer extends DatabaseProperty("jdbc:jtds:sqlserver://TTRAFLONSQL02.global.trafigura.com/ForwardCurves;instance=DB02", "curveUser", "jha12wsii")

  /**
   * EAI Starling is a copy of EAI Archive with some changes to allow versioning. It contains trades and book closes for Books 43 and 173
   */
  object EAIStarlingSqlServer extends DatabaseProperty("jdbc:jtds:sqlserver://TTRAFLONSQL12.global.trafigura.com/EAIStarling;instance=DB12", "starling", "ng1lr4ts123!Y^%&$")
  //  object EAIArchiveSqlServer extends DatabaseProperty("jdbc:jtds:sqlserver://TTRAFLONSQL02.global.trafigura.com/EAIArchive;instance=DB02", "starling", "ng1lr4ts123!Y^%&$")
  
  object TrinityDatabase extends DatabaseProperty("jdbc:oracle:thin:@LondonTrinityLiveDB.global.trafigura.com:1521:Trinity", "EXEC_IMP", "EXEC_IMP")
  object TrinityUploadDirectory extends StringProperty("/tmp/starling/trinity-upload")
  object EAIReplica extends DatabaseProperty("jdbc:jtds:sqlserver://TTRAFLONSQL12.global.trafigura.com/EAI;instance=DB12", "starling", "ng1lr4ts123!Y^%&$")
  object SoftmarDatabase extends DatabaseProperty("jdbc:jtds:sqlserver://TTRAFLONSQL01.global.trafigura.com/Softmar;instance=DB01", "starling", "ng1lr4ts123!Y^%&$")

  object GalenaDatabase extends DatabaseProperty("jdbc:oracle:thin:@//galenaprdcl01:1521/trinitygalenastarlingprd01.global.trafigura.com", "EXEC_IMP", "EXEC_IMP")

  // VarSqlServer refers to a database engine - not a database. All uses of it then specify database and table.
  object VarSqlServer extends DatabaseProperty("jdbc:jtds:sqlserver://TTRAFLONSQL12.global.trafigura.com/;instance=DB12", "starling", "ng1lr4ts123!Y^%&$")

  object DeltaCSVDir extends StringProperty(".")

  object KerberosPassword extends StringProperty("suvmerinWiv0")
  object ServerPrincipalName extends StringProperty("STARLING-TEST/dave-linux")
  object UseAuth extends BooleanProperty(false)
  object NoMP extends BooleanProperty(false)

  object LIMHost extends StringProperty("lim-london-live")
  object LIMPort extends IntProperty(6400)

  object NeptuneDatabase extends DatabaseProperty(
    "jdbc:jtds:sqlserver://TTRAFLON2K97.global.trafigura.com/Neptune",
    "starling_neptune",
    "1234142dfSdfS&%&^%£)"
   )

}

object Props {
  type PropAccessor = (Props) => PropsHelper#Property

  def main(args:Array[String]) {
    PropsHelper.main(args)
  }
}