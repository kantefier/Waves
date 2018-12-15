package com.wavesplatform.state

import com.wavesplatform.account.{Address, Alias, PublicKeyAccount}
import com.wavesplatform.block.{Block, BlockHeader}
import com.wavesplatform.state.reader.LeaseDetails
import com.wavesplatform.transaction.Transaction.Type
import com.wavesplatform.transaction.lease.LeaseTransaction
import com.wavesplatform.transaction.smart.script.Script
import com.wavesplatform.transaction._
import com.wavesplatform.transaction.assets.{IssueTransaction, IssueTransactionV1}
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

  override def height: Int = sql"SELECT max(height) FROM blocks".query[Int].unique.runSync

  override def score: BigInt = {
    for {
      h      <- sql"SELECT max(height) FROM blocks".query[Int].unique
      target <- sql"SELECT nxt_consensus_base_target FROM blocks WHERE height = $h".query[BigDecimal].unique
    } yield BigInt("18446744073709551616") / target.toBigInt()
  }.runSync

  override def scoreOf(blockId: AssetId): Option[BigInt] = {
    for {
      target <- sql"SELECT nxt_consensus_base_target FROM blocks WHERE signature = ${blockId.toString} ".query[BigDecimal].option
    } yield (target.map(t => BigInt("18446744073709551616") / t.toBigInt()))
  }.runSync

  override def blockHeaderAndSize(height: Int): Option[(BlockHeader, Int)] = {
    for {
      h <- sql"SELECT max(height) FROM blocks".query[Int].unique
    } yield ()

    ???
  }

  import DoobieGetInstances._

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

  def issueTx = {
    val v1 =
      sql"SELECT sender, asset_name AS name, description, quantity, decimals, reissuable, fee, time_stamp AS timestamp, signatur FROM issue_transaction"
        .query[IssueTransactionV1]
//    for {
//      version <- sql"SELECT tx_type FROM issue_transaction".query[Byte].unique
//    } sql"SELECT address, amount, time_stamp AS timestamp, signature FROM issue_transactions"
//      .query[IssueTransaction]
//      .unique
//      .runSync
  }

  override def blockHeaderAndSize(blockId: AssetId): Option[(BlockHeader, Int)] = ???

  override def lastBlock: Option[Block] = ???

  override def carryFee: Long = ???

  override def blockBytes(height: Int): Option[Array[Byte]] = ???

  override def blockBytes(blockId: AssetId): Option[Array[Byte]] = ???

  override def heightOf(blockId: AssetId): Option[Int] = ???

  /** Returns the most recent block IDs, starting from the most recent  one */
  override def lastBlockIds(howMany: Int): Seq[AssetId] = ???

  /** Returns a chain of blocks starting with the block with the given ID (from oldest to newest) */
  override def blockIdsAfter(parentSignature: AssetId, howMany: Int): Option[Seq[AssetId]] = ???

  override def parent(block: Block, back: Int): Option[Block] = ???

  /** Features related */
  override def approvedFeatures: Map[Short, Int] = ???

  override def activatedFeatures: Map[Short, Int] = ???

  override def featureVotes(height: Int): Map[Short, Int] = ???

  override def portfolio(a: Address): Portfolio = ???

  override def transactionInfo(id: AssetId): Option[(Int, Transaction)] = ???

  override def transactionHeight(id: AssetId): Option[Int] = ???

  override def addressTransactions(address: Address, types: Set[Type], count: Int, fromId: Option[AssetId]): Either[String, Seq[(Int, Transaction)]] =
    ???

  override def containsTransaction(tx: Transaction): Boolean = ???

  override def assetDescription(id: AssetId): Option[AssetDescription] = ???

  override def resolveAlias(a: Alias): Either[ValidationError, Address] = ???

  override def leaseDetails(leaseId: AssetId): Option[LeaseDetails] = ???

  override def filledVolumeAndFee(orderId: AssetId): VolumeAndFee = ???

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

//  implicit val issueTransactionGet: Get[IssueTransaction]  = {
//    Get[(Byte, )]
//  }

}
