package com.wavesplatform.state

import cats.data.OptionT
import com.wavesplatform.account.{Address, AddressOrAlias, Alias, PublicKeyAccount}
import com.wavesplatform.block.{Block, BlockHeader}
import com.wavesplatform.state.reader.LeaseDetails
import com.wavesplatform.transaction.Transaction.Type
import com.wavesplatform.transaction.lease.LeaseTransaction
import com.wavesplatform.transaction.smart.script.Script
import com.wavesplatform.transaction._
import com.wavesplatform.transaction.assets.{IssueTransaction, IssueTransactionV1, IssueTransactionV2}
import com.wavesplatform.transaction.transfer.{TransferTransaction, TransferTransactionV1, TransferTransactionV2}
import doobie.Get
import monix.eval.Task
import monix.execution.Scheduler
import scorex.crypto.encode.Base58
import scorex.crypto.signatures.PublicKey

trait Blockchain {
  def height: Int
  def score: BigInt
  def scoreOf(blockId: ByteStr): Option[BigInt]

  def blockHeaderAndSize(height: Int): Option[(BlockHeader, Int)]
  def blockHeaderAndSize(blockId: ByteStr): Option[(BlockHeader, Int)]

  def lastBlock: Option[Block]
  def carryFee: Long
  def blockBytes(height: Int): Option[Array[Byte]]
  def blockBytes(blockId: ByteStr): Option[Array[Byte]]

  def heightOf(blockId: ByteStr): Option[Int]

  /** Returns the most recent block IDs, starting from the most recent  one */
  def lastBlockIds(howMany: Int): Seq[ByteStr]

  /** Returns a chain of blocks starting with the block with the given ID (from oldest to newest) */
  def blockIdsAfter(parentSignature: ByteStr, howMany: Int): Option[Seq[ByteStr]]

  def parent(block: Block, back: Int = 1): Option[Block]

  /** Features related */
  def approvedFeatures: Map[Short, Int]
  def activatedFeatures: Map[Short, Int]
  def featureVotes(height: Int): Map[Short, Int]

  def portfolio(a: Address): Portfolio

  def transactionInfo(id: ByteStr): Option[(Int, Transaction)]
  def transactionHeight(id: ByteStr): Option[Int]

  def addressTransactions(address: Address,
                          types: Set[Transaction.Type],
                          count: Int,
                          fromId: Option[ByteStr]): Either[String, Seq[(Int, Transaction)]]

  def containsTransaction(tx: Transaction): Boolean

  def assetDescription(id: ByteStr): Option[AssetDescription]

  def resolveAlias(a: Alias): Either[ValidationError, Address]

  def leaseDetails(leaseId: ByteStr): Option[LeaseDetails]

  def filledVolumeAndFee(orderId: ByteStr): VolumeAndFee

  /** Retrieves Waves balance snapshot in the [from, to] range (inclusive) */
  def balanceSnapshots(address: Address, from: Int, to: Int): Seq[BalanceSnapshot]

  def accountScript(address: Address): Option[Script]
  def hasScript(address: Address): Boolean

  def assetScript(id: ByteStr): Option[Script]
  def hasAssetScript(id: ByteStr): Boolean

  def accountData(acc: Address): AccountDataInfo
  def accountData(acc: Address, key: String): Option[DataEntry[_]]

  def balance(address: Address, mayBeAssetId: Option[AssetId]): Long

  def assetDistribution(assetId: ByteStr): Map[Address, Long]
  def assetDistributionAtHeight(assetId: AssetId, height: Int, count: Int, fromAddress: Option[Address]): Either[ValidationError, Map[Address, Long]]
  def wavesDistribution(height: Int): Map[Address, Long]

  // the following methods are used exclusively by patches
  def allActiveLeases: Set[LeaseTransaction]

  /** Builds a new portfolio map by applying a partial function to all portfolios on which the function is defined.
    * @note Portfolios passed to `pf` only contain Waves and Leasing balances to improve performance */
  def collectLposPortfolios[A](pf: PartialFunction[(Address, Portfolio), A]): Map[Address, A]

  def append(diff: Diff, carryFee: Long, block: Block): Unit
  def rollbackTo(targetBlockId: ByteStr): Either[String, Seq[Block]]
}

class SqlDb(implicit scheduler: Scheduler) extends Blockchain {
  import scala.concurrent.duration._
  val chainId = com.wavesplatform.account.AddressScheme.current.chainId

  import doobie._
  import cats._
  import cats.effect._
  import cats.implicits._
  import doobie.implicits._

  val timeout = 2.seconds

  val xa = Transactor.fromDriverManager[Task](
    "org.postgresql.Driver", // driver classname
    "jdbc:postgresql:world", // connect URL (driver-specific)
    "postgres", // user
    "" // password
  )

  /**
    *
    * run your shit sql"SELECT * FROM BLOCKCHAIN".query[Int].unique.runBlocking
    *
    */
  private implicit class BlockingQuery[T](conn: ConnectionIO[T]) {
    def runSync: T = conn.transact(xa).runSyncUnsafe(timeout)
  }

  import DoobieGetInstances._

  override def height: Int = sql"SELECT max(height) FROM blocks".query[Int].unique.runSync

  override def score: BigInt = {
    for {
      h     <- sql"SELECT max(height) FROM blocks".query[Int].unique
      score <- sql"SELECT height_score FROM blocks WHERE height = $h".query[BigInt].unique
    } yield score
  }.runSync

  override def scoreOf(blockId: AssetId): Option[BigInt] = {
    for {
      refAndHeightScore <- OptionT.apply(
        sql"SELECT reference, height_score FROM blocks WHERE signature = '${blockId.toString}'".query[(ByteStr, BigInt)].option)
      (reference, heightScore) = refAndHeightScore
      previousHeightScore <- OptionT.apply(sql"SELECT height_score FROM blocks WHERE signature = '${reference.toString}'".query[BigInt].option)
    } yield heightScore - previousHeightScore
  }.value.runSync

  override def blockHeaderAndSize(height: Int): Option[(BlockHeader, Int)] = {
    for {
      h <- sql"SELECT max(height) FROM blocks".query[Int].unique
    } yield ()

    ???
  }

  def paymentTx = {

    sql"SELECT (sender_public_key, recipient, amount, fee, time_stamp AS timestamp, signature) FROM payment_transactions"
      .query[PaymentTransaction]
      .unique
      .runSync
  }

  def genesisTx = {
    sql"SELECT address, amount, time_stamp AS timestamp, signature FROM genesis_transactions"
      .query[GenesisTransaction]
      .unique
      .runSync
  }

  def issueTx: IssueTransaction = {
    val v1: Query0[IssueTransaction] =
      sql"SELECT sender, asset_name AS name, description, quantity, decimals, reissuable, fee, time_stamp AS timestamp, signature FROM issue_transaction"
        .query[IssueTransactionV1]
        .map(t => t.asInstanceOf[IssueTransaction])
    val v2: Query0[IssueTransaction] =
      sql"SELECT 2, $chainId, sender, asset_name AS name, description, quantity, decimals, reissuable, script, fee, time_stamp AS timestamp, proofs FROM issue_transaction"
        .query[IssueTransactionV2]
        .map(t => t.asInstanceOf[IssueTransaction])

    def chooseV(v: Byte): Query0[IssueTransaction] = {
      if (v == 1) v1 else v2
    }

    (for {
      v <- sql"SELECT tx_version FROM issue_transaction".query[Byte].unique
      q <- chooseV(v).unique
    } yield q).runSync
  }

  def transferTx = {
    val v1 =
      sql"SELECT sender, asset_name AS name, description, quantity, decimals, reissuable, fee, time_stamp AS timestamp, signature FROM issue_transaction"
        .query[TransferTransactionV1]
        .map(t => t.asInstanceOf[TransferTransaction])

    val v2 =
      sql"SELECT 2, $chainId, sender, asset_name AS name, description, quantity, decimals, reissuable, script, fee, time_stamp AS timestamp, proofs FROM issue_transaction"
        .query[TransferTransactionV2]
        .map(t => t.asInstanceOf[TransferTransaction])

    if (true) v1 else v2
  }

  override def blockHeaderAndSize(blockId: AssetId): Option[(BlockHeader, Int)] = ???

  override def lastBlock: Option[Block] = ???

  override def carryFee: Long = ???

  override def blockBytes(height: Int): Option[Array[Byte]] =
    sql"SELECT block_bytes from blocks WHERE height = $height".query[Array[Byte]].option.runSync

  override def blockBytes(blockId: ByteStr): Option[Array[Byte]] =
    sql"SELECT block_bytes from blocks WHERE signature = ${blockId.toString}".query[Array[Byte]].option.runSync

  override def heightOf(blockId: ByteStr): Option[Int] =
    sql"SELECT height from blocks WHERE signature = '${blockId.toString}'".query[Int].option.runSync

  /** Returns the most recent block IDs, starting from the most recent  one */
  override def lastBlockIds(howMany: Int): Seq[ByteStr] =
    sql"SELECT signature from blocks ORDER BY height DESC".query[ByteStr].stream.take(howMany).compile.toList.runSync.toSeq

  /** Returns a chain of blocks starting with the block with the given ID (from oldest to newest) */
  override def blockIdsAfter(parentSignature: AssetId, howMany: Int): Option[Seq[ByteStr]] = ???

  override def parent(block: Block, back: Int): Option[Block] = ??? /* {
    for {
      parentHeight <- OptionT(sql"SELECT height from blocks WHERE signature = '${block.reference.toString}'".query[Int].option)
      targetHeight = parentHeight - back + 1
      resultBlock <- blockAt(targetHeight)
    } ???
    ???
  }*/

  /** Features related */
  override def approvedFeatures: Map[Short, Int] = ???

  override def activatedFeatures: Map[Short, Int] = ???

  override def featureVotes(height: Int): Map[Short, Int] = ???

  override def portfolio(a: Address): Portfolio = ???

  override def transactionInfo(id: ByteStr): Option[(Int, Transaction)] = ???

  override def transactionHeight(id: ByteStr): Option[Int] = ???

  override def addressTransactions(address: Address, types: Set[Type], count: Int, fromId: Option[AssetId]): Either[String, Seq[(Int, Transaction)]] =
    ???

  override def containsTransaction(tx: Transaction): Boolean = ???

  override def assetDescription(id: AssetId): Option[AssetDescription] = ???

  override def resolveAlias(a: Alias): Either[ValidationError, Address] = ???

  override def leaseDetails(leaseId: ByteStr): Option[LeaseDetails] = ???

  override def filledVolumeAndFee(orderId: ByteStr): VolumeAndFee = ???

  /** Retrieves Waves balance snapshot in the [from, to] range (inclusive) */
  override def balanceSnapshots(address: Address, from: Int, to: Int): Seq[BalanceSnapshot] = ???

  override def accountScript(address: Address): Option[Script] = ???

  override def hasScript(address: Address): Boolean = ???

  override def assetScript(id: AssetId): Option[Script] = ???

  override def hasAssetScript(id: AssetId): Boolean = ???

  override def accountData(acc: Address): AccountDataInfo = ???

  override def accountData(acc: Address, key: String): Option[DataEntry[_]] = ???

  override def balance(address: Address, mayBeAssetId: Option[AssetId]): Long = ???

  override def assetDistribution(assetId: AssetId): Map[Address, Long] = ???

  override def assetDistributionAtHeight(assetId: AssetId,
                                         height: Int,
                                         count: Int,
                                         fromAddress: Option[Address]): Either[ValidationError, Map[Address, Long]] = ???

  override def wavesDistribution(height: Int): Map[Address, Long] = ???

  override def allActiveLeases: Set[LeaseTransaction] = ???

  /** Builds a new portfolio map by applying a partial function to all portfolios on which the function is defined.
    *
    * @note Portfolios passed to `pf` only contain Waves and Leasing balances to improve performance */
  override def collectLposPortfolios[A](pf: PartialFunction[(Address, Portfolio), A]): Map[Address, A] = ???
  override def append(diff: Diff, carryFee: Long, block: Block): Unit                                  = ???
  override def rollbackTo(targetBlockId: AssetId): Either[String, Seq[Block]]                          = ???
}

object DoobieGetInstances {
  import doobie.postgres._, doobie.postgres.implicits._

  implicit val bigIntGet: Get[BigInt] = {
    Get[BigDecimal].map(_.toBigInt())
  }

  implicit val arrayByteGet: Get[Array[Byte]] = {
    Get[String].map(s => Base58.decode(s).get)
  }

  implicit val byteStrGet: Get[ByteStr] = {
    Get[String].map(s => ByteStr.decodeBase58(s).get)
  }

  implicit val pkGet: Get[PublicKeyAccount] = {
    Get[String].map(s => PublicKeyAccount.fromBase58String(s).right.get)
  }

  implicit val addressGet: Get[Address] = {
    Get[String].map(s => Address.fromString(s).right.get)
  }

  implicit val proofsGet: Get[Proofs] =
    Get[Array[String]].map { s =>
      Proofs(s.map(x => ByteStr.decodeBase58(x).get))
    }

  implicit val scriptGet: Get[Script] = {
    Get[String].map { s =>
      Script.fromBase64String(s).right.get
    }
  }

  implicit val addressOrAlias: Get[AddressOrAlias] = {
    Get[String].map { s =>
      AddressOrAlias.fromString(s).right.get
    }
  }

//  implicit val issueTransactionGet: Get[IssueTransaction]  = {
//    Get[(Byte, )]
//  }

}
