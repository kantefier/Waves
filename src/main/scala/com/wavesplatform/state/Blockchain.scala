package com.wavesplatform.state

import cats.data.{NonEmptyList, OptionT}
import com.wavesplatform.account.{Address, AddressOrAlias, Alias, PublicKeyAccount}
import com.wavesplatform.block.{Block, BlockHeader}
import com.wavesplatform.settings.FunctionalitySettings
import com.wavesplatform.state.reader.LeaseDetails
import com.wavesplatform.transaction.Transaction.Type
import com.wavesplatform.transaction.ValidationError.AliasDoesNotExist
import com.wavesplatform.transaction._
import com.wavesplatform.transaction.assets.exchange.{ExchangeTransaction, _}
import com.wavesplatform.transaction.assets.{IssueTransaction, IssueTransactionV1, IssueTransactionV2, _}
import com.wavesplatform.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import com.wavesplatform.transaction.smart.SetScriptTransaction
import com.wavesplatform.transaction.smart.script.Script
import com.wavesplatform.transaction.transfer.{MassTransferTransaction, TransferTransaction, TransferTransactionV1, TransferTransactionV2}
import doobie.util.{Meta, Read, Write, _}
import monix.eval.Task
import monix.execution.Scheduler
import org.postgresql.util.PGobject
import play.api.libs.json.Json
import scorex.crypto.encode.Base58

import scala.collection.mutable

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
    *
    * @note Portfolios passed to `pf` only contain Waves and Leasing balances to improve performance */
  def collectLposPortfolios[A](pf: PartialFunction[(Address, Portfolio), A]): Map[Address, A]

  def append(diff: Diff, carryFee: Long, block: Block): Unit
  def rollbackTo(targetBlockId: ByteStr): Either[String, Seq[Block]]
}

class SqlDb(fs: FunctionalitySettings)(implicit scheduler: Scheduler) extends Blockchain {
  import scala.concurrent.duration._
  def chainId() = com.wavesplatform.account.AddressScheme.current.chainId

  import cats._
  import cats.implicits._
  import doobie._
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

  override def height: Int = sql"SELECT max(height) FROM blocks".query[Option[Int]].stream.compile.toList.runSync.headOption.flatten.getOrElse(0)

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

    sql"""SELECT (sender_public_key, recipient, amount, fee, time_stamp AS timestamp, signature)
         |FROM payment_transactions"""
      .query[PaymentTransaction]
      .unique
      .runSync
  }

  def genesisTx = {
    sql"""SELECT address, amount, time_stamp AS timestamp, signature
         |FROM genesis_transactions"""
      .query[GenesisTransaction]
      .unique
      .runSync
  }

  def issueTx: IssueTransaction = {
    val v1: Query0[IssueTransaction] =
      sql"""SELECT sender, asset_name AS name, description, quantity, decimals, reissuable, fee, time_stamp AS timestamp, signature
           |FROM issue_transaction"""
        .query[IssueTransactionV1]
        .map(t => t.asInstanceOf[IssueTransaction])
    val v2: Query0[IssueTransaction] =
      sql"""SELECT 2, ${chainId()}, sender, asset_name AS name, description, quantity, decimals, reissuable, script, fee, time_stamp AS timestamp, proofs
           |FROM issue_transaction"""
        .query[IssueTransactionV2]
        .map(t => t.asInstanceOf[IssueTransaction])

    def chooseV(v: Byte): Query0[IssueTransaction] = {
      if (v == 1) v1 else v2
    }

    (for {
      v <- sql"""SELECT tx_version
             |FROM issue_transaction""".query[Byte].unique
      q <- chooseV(v).unique
    } yield q).runSync
  }

  def exTx = {
    sql"".query[ExchangeTransactionV1]
    sql"".query[SetScriptTransaction]
    sql"".query[BooleanDataEntry]
    sql"".query[BinaryDataEntry]
    sql"".query[StringDataEntry]
  }

  private val transferTxV1Fragment =
    fr"""SELECT asset_id AS assetId, sender, recipient, amount, time_stamp AS timestamp, fee_asset AS feeAssetId, fee, attachment, signature
        |FROM issue_transaction""".stripMargin

  private val transferTxV2Fragment =
    fr"""SELECT 2, sender, recipient, asset_id AS assetId, amount, time_stamp AS timestamp, fee_asset AS feeAssetId, fee, attachment, proofs
        |FROM issue_transaction""".stripMargin

  def transferTxOnHeight(height: Int) = {
    val v1s = (transferTxV1Fragment ++ whereHeight(height))
      .query[TransferTransactionV1]
      .map(t => t.asInstanceOf[TransferTransaction])
      .stream
      .compile
      .toList

    val v2s = (transferTxV2Fragment ++ whereHeight(height))
      .query[TransferTransactionV2]
      .map(t => t.asInstanceOf[TransferTransaction])
      .stream
      .compile
      .toList

    (for (v1 <- v1s; v2 <- v2s) yield v1 ++ v2).runSync
  }

  def insertTransfer(tx: TransferTransaction, height: Int) = {
    val insert = if (tx.version == 1) {
      val txV1 = tx.asInstanceOf[TransferTransactionV1]
      sql"""
          |INSERT INTO transfer_transactions
          |(height, tx_type, id, time_stamp, signature, tx_version,
          |sender, sender_public_key, tx_bytes, fee, asset_id, amount,
          |recipient, fee_asset, attachment)
          |VALUES
          |($height, ${TransferTransaction.typeId}, ${txV1.id()}, ${toTimestamp(txV1.timestamp)}, ${txV1.signature}, ${txV1.version},
          |${txV1.sender.toAddress}, ${Base58.encode(txV1.sender.publicKey)}, ${Base58.encode(tx.bytes())}, ${txV1.fee}, ${txV1.assetId}, ${txV1.amount},
          |${txV1.recipient}, ${txV1.feeAssetId}, ${txV1.attachment})
        """.stripMargin
    } else {
      val txV2 = tx.asInstanceOf[TransferTransactionV2]
      sql"""
           |INSERT INTO transfer_transactions
           |(height, tx_type, id, time_stamp, proofs, tx_version,
           |sender, sender_public_key, tx_bytes, fee, asset_id, amount,
           |recipient, fee_asset, attachment)
           |VALUES
           |($height, ${TransferTransaction.typeId}, ${txV2.id()}, ${toTimestamp(txV2.timestamp)}, ${txV2.proofs}, ${txV2.version},
           |${txV2.sender.toAddress}, ${Base58.encode(txV2.sender.publicKey)}, ${Base58.encode(tx.bytes())}, ${txV2.fee}, ${txV2.assetId}, ${txV2.amount},
           |${txV2.recipient}, ${txV2.feeAssetId}, ${txV2.attachment})
        """.stripMargin
    }

    insert.update.run.runSync
  }

  def transferTxById(id: ByteStr) = {
    val v1s = (transferTxV1Fragment ++ whereId(id))
      .query[TransferTransactionV1]
      .map(t => t.asInstanceOf[TransferTransaction])
      .option

    val v2s = (transferTxV2Fragment ++ whereId(id))
      .query[TransferTransactionV1]
      .map(t => t.asInstanceOf[TransferTransaction])
      .option

    (for (v1 <- v1s; v2 <- v2s) yield v1.orElse(v1)).runSync
  }

  def datadataTxFr(txId: ByteStr) =
    sql"""
        |SELECT position_in_tx, data_key, data_type, data_value_integer, data_value_boolean, data_value_binary, data_value_string
        | FROM data_transactions_data
        | WHERE tx_id = $txId
      """.stripMargin
      .query[(Int, DataEntry[_])]

  def dataTx(height: Int) = {
    val q = fr"""SELECT id, 1, sender, fee, time_stamp AS timestamp, proofs
        | FROM data_transactions
        | WHERE height = $height"""
      .query[(ByteStr, Byte, PublicKeyAccount, Long, Long, Proofs)]

    for {
      list <- q.stream.compile.toList
    } yield {
      list.map {
        case (id, version, sender, fee, timestamp, proofs) =>
          val des = datadataTxFr(id).stream.compile.toList.runSync.sortWith { case (f, s) => f._1 < s._1 }
          DataTransaction.create(version, sender, des.map(_._2), fee, timestamp, proofs)
      }
    }
  }

  def toTimestamp(ts: Long) = new java.sql.Timestamp(ts)

  def insertData(tx: DataTransaction, height: Int) = {
    import cats._, cats.data._, cats.implicits._

    val sql = s"""
         |INSERT INTO data_transactions_data
         | (height, tx_id, position_in_tx, data_key, data_type, data_value_integer, data_value_boolean, data_value_binary, data_value_string)
         | VALUES
         |($height, ?, ?, ?, ?, ?, ?, ?, ?)
         |
       """.stripMargin

    val list: List[(AssetId, Int, DataEntry[_])] = tx.data.zipWithIndex.map {
      case (e, pos) =>
        (tx.id(), pos, e)
    }

    val updateEntries = Update[(ByteStr, Int, DataEntry[_])](sql).updateMany(list)

    val update =
      sql"""
        |INSERT INTO data_transactions
        |(height, tx_type, id, time_stamp, proofs, tx_version, sender, sender_public_key, tx_bytes, fee)
        | VALUES
        | ($height, ${DataTransaction.typeId}, ${tx
             .id()}, ${toTimestamp(tx.timestamp)}, ${tx.proofs}, ${tx.version}, ${tx.sender.toAddress}, ${Base58.encode(tx.sender.publicKey)}, ${Base58
             .encode(tx.bytes())}, ${tx.fee})
      """.stripMargin.update.run

    (for {
      y <- update
      x <- updateEntries
    } yield ()).runSync
  }

  def insertGenesisTransaction(tx: GenesisTransaction, height: Int) = {
    sql"""
         |INSERT INTO genesis_transactions
         |(height, tx_type, id, time_stamp, signature, tx_bytes, recipient, amount, fee)
         | VALUES
         | ($height, ${GenesisTransaction.typeId}, ${tx
           .id()}, ${toTimestamp(tx.timestamp)}, ${tx.signature}, ${Base58.encode(tx.bytes())}, ${tx.recipient}, ${tx.amount}, 0)
      """.stripMargin.update.run.runSync

  }

  override def blockHeaderAndSize(blockId: AssetId): Option[(BlockHeader, Int)] = ???

  override def lastBlock: Option[Block] = {
    for {
      currentHeight <- sql"SELECT max(height) FROM blocks".query[Option[Int]].stream.compile.toList.runSync.headOption.flatten
      blockBytes = sql"SELECT block_bytes FROM blocks WHERE height = $currentHeight".query[String].unique.runSync
    } yield Base58.decode(blockBytes).flatMap(Block.parseBytes).get
  }

  override def carryFee: Long = {
    for {
      currentHeight <- sql"SELECT max(height) FROM blocks".query[Option[Int]].stream.compile.toList.runSync.headOption.flatten
      blockFee = sql"SELECT carry_fee FROM blocks WHERE height = $currentHeight".query[Long].unique.runSync
    } yield blockFee
  }.getOrElse(0)

  override def blockBytes(height: Int): Option[Array[Byte]] =
    sql"SELECT block_bytes FROM blocks WHERE height = $height".query[Array[Byte]].option.runSync

  override def blockBytes(blockId: ByteStr): Option[Array[Byte]] =
    sql"SELECT block_bytes FROM blocks WHERE signature = '${blockId.toString}'".query[Array[Byte]].option.runSync

  override def heightOf(blockId: ByteStr): Option[Int] =
    sql"SELECT height FROM blocks WHERE signature = '${blockId.toString}'".query[Int].option.runSync

  /** Returns the most recent block IDs, starting from the most recent  one */
  override def lastBlockIds(howMany: Int): Seq[ByteStr] =
    sql"SELECT signature FROM blocks ORDER BY height DESC".query[ByteStr].stream.take(howMany).compile.toList.runSync

  /** Returns a chain of blocks starting with the block with the given ID (from oldest to newest) */
  override def blockIdsAfter(parentSignature: ByteStr, howMany: Int): Option[Seq[ByteStr]] = {
    val parentHeight = heightOf(parentSignature).get
    val blockIds =
      sql"SELECT signature FROM blocks WHERE height >= $parentHeight ORDER BY height ASC".query[ByteStr].stream.take(howMany).compile.toList.runSync
    Option(blockIds).filter(_.nonEmpty)
  }

  override def parent(block: Block, back: Int): Option[Block] = {
    /*for {
      parentHeight <- OptionT(sql"SELECT height from blocks WHERE signature = '${block.reference.toString}'".query[Int].option)
      targetHeight = parentHeight - back + 1
//      resultBlock <- blockAt(targetHeight)
    } yield ???

    type lamb[A] = ConnectionIO[Option[A]]

    for {
      givenHeight <- OptionT(sql"""SELECT height FROM blocks WHERE signature=${block.uniqueId}""".query[Int].option)
      targetHeight = givenHeight - back
      blockBytes  <- OptionT(sql"SELECT block_bytes FROM blocks WHERE height=$targetHeight".query[Array[Byte]].option)
      resultBlock <- Block.parseBytes(blockBytes).toOption.liftTo[lamb]
    } yield resultBlock*/

    ???
  }

  /** Features related */
  override def approvedFeatures: Map[Short, Int] = {
    sql"SELECT id, height FROM approved_features"
      .query[(Short, Int)]
      .stream
      .compile
      .toList
      .runSync
      .toMap
  }

  override def activatedFeatures: Map[Short, Int] = {
    sql"SELECT id, height FROM activated_features"
      .query[(Short, Int)]
      .stream
      .compile
      .toList
      .runSync
      .toMap ++ fs.preActivatedFeatures
  }

  override def featureVotes(height: Int): Map[Short, Int] = {
    fs.activationWindow(height)
      .flatMap(h => blockHeaderAndSize(height).fold(Seq.empty[Short])(_._1.featureVotes.toSeq))
      .groupBy(identity)
      .mapValues(_.size)
  }

  def currentWavesBalanceIo(addressId: BigInt): ConnectionIO[Long] =
    sql"SELECT amount FROM waves_balances WHERE address_id=$addressId AND height = (SELECT max(height) FROM waves_balances WHERE address_id=$addressId)"
      .query[Long]
      .option
      .map(_.getOrElse(0L))

  def currentLeaseBalanceIo(addressId: BigInt): ConnectionIO[LeaseBalance] =
    sql"SELECT lease_in, lease_out FROM lease_balance WHERE address_id=$addressId AND height = (SELECT max(height) FROM lease_balance WHERE address_id=$addressId)"
      .query[(Long, Long)]
      .map { case (in, out) => LeaseBalance(in, out) }
      .option
      .map(_.getOrElse(LeaseBalance(0L, 0L)))

  def currentAssetBalances(addressId: BigInt): ConnectionIO[Map[AssetId, Long]] =
    sql"""SELECT t1.asset_id, t1.amount
         | FROM asset_balances t1
         | INNER JOIN (
         |  SELECT address_id, asset_id, max(height) as maxheight
         |  FROM asset_balances
         |  WHERE address_id = $addressId
         |  GROUP BY address_id, asset_id) t2
         |    ON t1.address_id = t2.address_id
         |    AND t1.asset_id = t2.asset_id
         |    AND t1.height = t2.maxheight""".stripMargin
      .query[(AssetId, Long)]
      .stream
      .compile
      .toList
      .map(_.toMap)

  override def portfolio(a: Address): Portfolio = {
    for {
      addressId        <- OptionT(addressIdForAddress(a))
      wavesBalance     <- OptionT[ConnectionIO, Long](currentWavesBalanceIo(addressId).map(_.some))
      leaseBalance     <- OptionT[ConnectionIO, LeaseBalance](currentLeaseBalanceIo(addressId).map(_.some))
      assetToAmountMap <- OptionT[ConnectionIO, Map[AssetId, Long]](currentAssetBalances(addressId).map(_.some))
    } yield Portfolio(wavesBalance, leaseBalance, assetToAmountMap)
  }.value.runSync.getOrElse(Portfolio.empty)

  override def transactionInfo(id: ByteStr): Option[(Int, Transaction)] = {
    for {
      (height, txType, txVersion, bytes) <- sql"SELECT height, tx_type, tx_version, tx_bytes FROM transactions WHERE id = ${id.toString}"
        .query[Option[(Int, Short, Short, String)]]
        .stream
        .compile
        .toList
        .runSync
        .headOption
        .flatten
      parser <- TransactionParsers.by(txType.toByte, txVersion.toByte)
    } yield (height, parser.parseBytes(Base58.decode(bytes).get).get)
  }

  override def transactionHeight(id: ByteStr): Option[Int] = {
    sql"SELECT height FROM transactions WHERE id = ${id.toString}"
      .query[Int]
      .option
      .runSync
  }

  override def addressTransactions(address: Address, types: Set[Type], count: Int, fromId: Option[AssetId]): Either[String, Seq[(Int, Transaction)]] =
    ???

  override def containsTransaction(tx: Transaction): Boolean = {
    sql"SELECT id FROM transactions WHERE id = ${tx.id().toString}"
      .query[String]
      .option
      .runSync
      .isDefined
  }

  override def assetDescription(id: AssetId): Option[AssetDescription] = {
    transactionInfo(id) match {
      case Some((_, i: IssueTransaction)) =>
        val ai = getLastAssetInfo(id).getOrElse(AssetInfo(i.reissuable, i.quantity))
//        val sponsorship = db.fromHistory(Keys.sponsorshipHistory(assetId), Keys.sponsorship(assetId)).fold(0L)(_.minFee)
//        val script      = db.fromHistory(Keys.assetScriptHistory(assetId), Keys.assetScript(assetId)).flatten
        Some(AssetDescription(i.sender, i.name, i.description, i.decimals, ai.isReissuable, ai.volume, None, 0))
      case _ => None
    }
  }

  def addressIdForAddress(address: Address): ConnectionIO[Option[BigInt]] =
    sql"SELECT id FROM addresses WHERE address = ${address.toString}".query[BigInt].option

  override def resolveAlias(a: Alias): Either[ValidationError, Address] = {
    //TODO: check alias disabled (there's no such table currently, maybe we'll add it as s field to aliases table)
    (for {
      addressId <- OptionT(sql"SELECT address_id FROM aliases WHERE alias =${a.toString}".query[BigInt].option)
      address   <- OptionT(sql"SELECT address FROM addresses WHERE id = $addressId".query[Address].option)
    } yield address).value.runSync
      .toRight(AliasDoesNotExist(a))
  }

  override def leaseDetails(leaseId: ByteStr): Option[LeaseDetails] = ???

  override def filledVolumeAndFee(orderId: ByteStr): VolumeAndFee = {
    getVolumeAndFeeForOrder(orderId).getOrElse(VolumeAndFee.empty)
  }

  /** Retrieves Waves balance snapshot in the [from, to] range (inclusive) */
  override def balanceSnapshots(address: Address, from: Int, to: Int): Seq[BalanceSnapshot] = ???

  override def accountScript(address: Address): Option[Script] = {
    for {
      addressId  <- OptionT(addressIdForAddress(address))
      lastHeight <- OptionT(sql"SELECT max(height) FROM account_script_history WHERE account_id = $addressId".query[Int].option)
      scriptOpt <- OptionT(
        sql"SELECT script FROM address_scripts_at_height WHERE address_id = $addressId AND height = $lastHeight".query[Script].option)
    } yield scriptOpt
  }.value.runSync

  override def hasScript(address: Address): Boolean = accountScript(address).isDefined

  override def assetScript(id: AssetId): Option[Script] = {
    for {
      lastHeight <- sql"SELECT max(height) FROM assets_script_history WHERE asset_id = ${id.toString}"
        .query[Option[Int]]
        .stream
        .compile
        .toList
        .runSync
        .headOption
        .flatten
      scriptOpt <- sql"SELECT script FROM asset_scripts_at_height WHERE asset_id = ${id.toString} AND height = $lastHeight"
        .query[Script]
        .option
        .runSync
    } yield scriptOpt
  }

  override def hasAssetScript(id: AssetId): Boolean = assetScript(id).isDefined

  override def accountData(acc: Address): AccountDataInfo = {
    val addressId = getAddressId(acc)

    val list = sql"""SELECT dtd.data_key, dtd.data_type, dtd.data_value_integer, dtd.data_value_boolean, dtd.data_value_binary, dtd.data_value_string
         | FROM data_transactions_data AS dtd,
         | (SELECT dh.key AS key, MAX(dh.height) AS max_height FROM data_history AS dh WHERE dh.address_id = $addressId GROUP BY dh.key) as t
         | WHERE dtd.data_key = t.key AND dtd.height = t.max_height""".stripMargin
      .query[DataEntry[_]]
      .stream
      .compile
      .toList
      .runSync
    AccountDataInfo(list.map(de => de.key -> de).toMap)
  }

  override def accountData(acc: Address, key: String): Option[DataEntry[_]] = {
    val addressId = getAddressId(acc)

    sql"""SELECT dtd.data_key, dtd.data_type, dtd.data_value_integer, dtd.data_value_boolean, dtd.data_value_binary, dtd.data_value_string
         | FROM data_transactions_data AS dtd,
         | (SELECT dh.key AS key, MAX(dh.height) AS max_height FROM data_history AS dh WHERE dh.address_id = $addressId AND dh.key = $key GROUP BY dh.key) as t
         | WHERE dtd.data_key = t.key AND dtd.height = t.max_height""".stripMargin
      .query[DataEntry[_]]
      .option
      .runSync
  }

  override def balance(address: Address, mayBeAssetId: Option[AssetId]): Long = {
    sql"SELECT id FROM addresses WHERE address = ${address.toString}"
      .query[BigInt]
      .unique
      .flatMap { addressId =>
        mayBeAssetId match {
          case Some(assetId) =>
            sql"""SELECT amount
                 | FROM asset_balances
                 | WHERE address_id = $addressId
                 |  AND asset_id = $assetId
                 |  AND height = (
                 |    SELECT max(height)
                 |    FROM assets_balances
                 |    WHERE address_id = $addressId
                 |      AND asset_id = $assetId)"""
              .query[Long]
              .option
              .map(_.getOrElse(0L))

          case None =>
            currentWavesBalanceIo(addressId)
        }
      }
      .runSync
  }

  override def assetDistribution(assetId: AssetId): Map[Address, Long] = {
    sql"""SELECT t3.address, t1.amount
         | FROM asset_balances t1
         |  INNER JOIN (
         |    SELECT address_id, max(height) as maxheight
         |    FROM asset_balances
         |    WHERE asset_id = $assetId
         |    GROUP BY address_id) t2
         |      ON t1.address_id = t2.address_id AND t1.height = t2.maxheight
         |  INNER JOIN addresses t3
         |      ON t1.address_id = t3.id""".stripMargin
      .query[(Address, Long)]
      .stream
      .compile
      .toList
      .map(_.toMap)
      .runSync
  }

  override def assetDistributionAtHeight(assetId: AssetId,
                                         height: Int,
                                         count: Int,
                                         fromAddress: Option[Address]): Either[ValidationError, Map[Address, Long]] = ???

  override def wavesDistribution(height: Int): Map[Address, Long] = {
    sql"""SELECT t3.address, t1.amount
         | FROM waves_balances t1
         |  INNER JOIN (
         |    SELECT address_id, max(height) as maxheight
         |    FROM waves_balances
         |    WHERE height <= $height
         |    GROUP BY address_id) t2
         |      ON t1.address_id = t2.address_id AND t1.height = t2.maxheight
         |  INNER JOIN addresses t3
         |      ON t1.address_id = t3.id""".stripMargin
      .query[(Address, Long)]
      .stream
      .compile
      .toList
      .map(_.toMap)
      .runSync
  }

  override def allActiveLeases: Set[LeaseTransaction] = Set.empty

  /** Builds a new portfolio map by applying a partial function to all portfolios on which the function is defined.
    *
    * @note Portfolios passed to `pf` only contain Waves and Leasing balances to improve performance */
  override def collectLposPortfolios[A](pf: PartialFunction[(Address, Portfolio), A]): Map[Address, A] = Map.empty

  private def getAddressId(address: Address): Long = {
    sql"SELECT id FROM addresses WHERE address = ${address.stringRepr}"
      .query[Long]
      .option
      .runSync
      .getOrElse(throw new RuntimeException("Address not found"))
  }

  private def getExistingAddresses(addresses: Iterable[Address]): List[Address] = {
    val addressesString = addresses.map(_.toString).mkString("'", "', '", "'")
    sql"SELECT address FROM addresses WHERE address IN ($addressesString)"
      .query[String]
      .stream
      .map(Address.fromString(_).right.get)
      .compile
      .toList
      .runSync
  }

  private def getExistingAssets(addressId: Long): List[ByteStr] = {
    sql"SELECT asset_id FROM addresses_assets WHERE address_id = $addressId"
      .query[String]
      .stream
      .map(ByteStr.decodeBase58(_).get)
      .compile
      .toList
      .runSync
  }

  private def getVolumeAndFeeForOrder(orderId: ByteStr): Option[VolumeAndFee] = {
    sql"SELECT volume, fee FROM volume_and_fee_for_order_at_height WHERE order_id = ${orderId.toString} AND height = (SELECT MAX(height) FROM volume_and_fee_for_order_at_height t WHERE t.order_id = ${orderId.toString})"
      .query[(Long, Long)]
      .stream
      .map(f => VolumeAndFee(f._1, f._2))
      .compile
      .toList
      .runSync
      .headOption
  }

  private def getLastAssetInfo(assetId: ByteStr): Option[AssetInfo] = {
    sql"SELECT is_reissuable, volume FROM assets_info WHERE asset_id = ${assetId.toString} AND height = (SELECT MAX(height) FROM assets_info ai2 WHERE ai2.asset_id = ${assetId.toString})"
      .query[AssetInfo]
      .option
      .runSync
  }

  private def insertAdresses(addresses: Iterable[Address]): Map[Long, Address] = {
    val result = for (address <- addresses) yield {
      sql"insert into addresses (address) values (${address.toString})".update.withUniqueGeneratedKeys[(Long, String)]("id", "address").runSync
    }
    result.toMap.mapValues(Address.fromString(_).right.get)
  }

  def insertBlock(block: Block, height: Int, carryFee: Long): Unit = {
    val prevlockScore: BigInt = lastBlock.map(_.blockScore()).getOrElse(0)
    val reference             = Option(block.reference).map(_.toString).orNull
    val version               = block.version.toShort
    val timestamp             = block.timestamp
    val baseTarget            = block.consensusData.baseTarget
    val genSign               = block.consensusData.generationSignature.toString
    val generator             = Base58.encode(block.signerData.generator.publicKey)
    val blockSign             = block.uniqueId.toString
    val size                  = block.transactionData.size
    val features              = block.featureVotes.mkString(",")
    val score                 = (block.blockScore() + prevlockScore).toString()
    val bytes                 = Base58.encode(block.bytes())
    sql"insert into blocks values ($version, $timestamp, $reference, $baseTarget, $genSign, $generator, $blockSign, 0, $size, $height, $features, $bytes, $score, $carryFee)".update.run.runSync
  }

  private def insertWavesBalance(addressId: Long, height: Int, balance: Long): Unit = {
    sql"insert into waves_balances values ($addressId, $height, $balance)".update.run.runSync
  }

  private def insertLeaseBalance(addressId: Long, height: Int, in: Long, out: Long): Unit = {
    sql"insert into lease_balance_at_height values ($addressId, $height, $in, $out)".update.run.runSync
  }

  private def insertNewAssets(addressId: Long, assetsId: Set[ByteStr]): Unit = {
    for (assetId <- assetsId) {
      sql"insert into addresses_assets values ($addressId, ${assetId.toString})".update.run.runSync
    }
  }

  private def insertAssetBalance(addressId: Long, assetId: ByteStr, height: Int, balance: Long): Unit = {
    sql"insert into asset_balances values ($addressId, $height, ${assetId.toString}, $balance)".update.run.runSync
  }

  private def insertVolumeAndFee(orderId: ByteStr, height: Int, volumeAndFee: VolumeAndFee): Unit = {
    sql"insert into volume_and_fee_for_order_at_height values (${orderId.toString}, $height, ${volumeAndFee.volume}, ${volumeAndFee.fee})".update.run.runSync
  }

  private def insertAssetInfo(assetId: ByteStr, assetInfo: AssetInfo, height: Int): Unit = {
    sql"insert into assets_info values (${assetId.toString}, ${assetInfo.isReissuable}, ${assetInfo.volume}, $height)".update.run.runSync
  }

  private def insertDataEntry(txId: ByteStr, addressId: Long, dataEntry: DataEntry[_]) = {}

  private def insertDataHistory(addressId: Long, key: String, height: Int) = {
    sql"insert into data_history values (${addressId.toString}, $key, $height)".update.run.runSync
  }

  private def insertApprovedFeatures(approvedFeatures: Map[Short, Int]): Unit = {
    for ((feature, height) <- approvedFeatures) {
      sql"insert into approved_features values ($feature, $height)".update.run.runSync
    }
  }

  private def insertActivatedFeatures(activatedFeatures: Map[Short, Int]): Unit = {
    for ((feature, height) <- activatedFeatures) {
      sql"insert into activated_features values ($feature, $height)".update.run.runSync
    }
  }

  override def append(diff: Diff, carryFee: Long, block: Block): Unit = {
    val currentHeight = height
    val newHeight     = currentHeight + 1

    val allAddresses = diff.portfolios.keys ++ diff.transactions.values.flatMap(_._3.toIterable)
    val newAddresses = {
      if (allAddresses.isEmpty) {
        Set.empty
      } else {
        val existingAddresses = getExistingAddresses(allAddresses)
        allAddresses.filterNot(existingAddresses.contains)
      }
    }
    val newInsertedAddress = insertAdresses(newAddresses)

    def addressId(address: Address): Long =
      newInsertedAddress.find(p => p._2 == address).map(_._1).getOrElse(getAddressId(address))

    val wavesBalances = Map.newBuilder[Long, Long]
    val assetBalances = Map.newBuilder[Long, Map[ByteStr, Long]]
    val leaseBalances = Map.newBuilder[Long, LeaseBalance]
    val newPortfolios = Map.newBuilder[Address, Portfolio]

    for ((address, portfolioDiff: Portfolio) <- diff.portfolios) {
      val newPortfolio = portfolio(address).combine(portfolioDiff)
      if (portfolioDiff.balance != 0) {
        wavesBalances += addressId(address) -> newPortfolio.balance
      }

      if (portfolioDiff.lease != LeaseBalance.empty) {
        leaseBalances += addressId(address) -> newPortfolio.lease
      }

      if (portfolioDiff.assets.nonEmpty) {
        val newAssetBalances = for { (k, v) <- portfolioDiff.assets if v != 0 } yield k -> newPortfolio.assets(k)
        if (newAssetBalances.nonEmpty) {
          assetBalances += addressId(address) -> newAssetBalances
        }
      }

      newPortfolios += address -> newPortfolio
    }

    val newFills = for {
      (orderId, fillInfo) <- diff.orderFills
    } yield orderId -> getVolumeAndFeeForOrder(orderId).getOrElse(VolumeAndFee.empty).combine(fillInfo)

    insertBlock(block, newHeight, carryFee)

    // TODO: insert transactions
    val newTransactions = Map.newBuilder[ByteStr, (Transaction, Set[Long])]
    for ((id, (_, tx, addresses)) <- diff.transactions) {
      tx match {
        case t: TransferTransaction =>
          insertTransfer(t, height)
        case d: DataTransaction =>
          insertData(d, height)
        case g: GenesisTransaction =>
          insertGenesisTransaction(g, height)
        case _ => println("oops")
      }
      newTransactions += id -> ((tx, addresses.map(addressId)))
    }

    for ((addressId, balance) <- wavesBalances.result()) {
      insertWavesBalance(addressId, newHeight, balance)
    }

    for ((addressId, leaseBalance: LeaseBalance) <- leaseBalances.result()) {
      insertLeaseBalance(addressId, newHeight, leaseBalance.in, leaseBalance.out)
    }

    val newAddressesForAsset = mutable.AnyRefMap.empty[ByteStr, Set[BigInt]]
    for ((addressId, assets) <- assetBalances.result()) {
      val prevAssets = getExistingAssets(addressId).toSet
      val newAssets  = assets.keySet.diff(prevAssets)
      for (assetId <- newAssets) {
        newAddressesForAsset += assetId -> (newAddressesForAsset.getOrElse(assetId, Set.empty) + addressId)
      }
      insertNewAssets(addressId, newAssets)
      for ((assetId, balance) <- assets) {
        insertAssetBalance(addressId, assetId, newHeight, balance)
      }
    }

    for ((orderId, volumeAndFee) <- newFills) {
      insertVolumeAndFee(orderId, newHeight, volumeAndFee)
    }

    for ((assetId, assetInfo) <- diff.issuedAssets) {
      val combinedAssetInfo = getLastAssetInfo(assetId).fold(assetInfo) { p =>
        Monoid.combine(p, assetInfo)
      }
      insertAssetInfo(assetId, combinedAssetInfo, newHeight)
    }

    for ((address, addressData) <- diff.accountData) {
      for ((key, value) <- addressData.data) {
        // TODO: insertDataEntry
        insertDataHistory(addressId(address), key, newHeight)
      }
    }

    val activationWindowSize = fs.activationWindowSize(newHeight)
    if (newHeight % activationWindowSize == 0) {
      val minVotes = fs.blocksForFeatureActivation(newHeight)
      val newlyApprovedFeatures = featureVotes(newHeight).collect {
        case (featureId, voteCount) if voteCount + (if (block.featureVotes(featureId)) 1 else 0) >= minVotes => featureId -> newHeight
      }

      if (newlyApprovedFeatures.nonEmpty) {
        insertApprovedFeatures(newlyApprovedFeatures)
        val featuresToSave = newlyApprovedFeatures.mapValues(_ + activationWindowSize)
        insertActivatedFeatures(featuresToSave)
      }
    }
  }

  override def rollbackTo(targetBlockId: AssetId): Either[String, Seq[Block]] = Right(Seq.empty)

  val txTypeIdToTable = Map(
    PaymentTransaction.typeId        -> "payment_transactions",
    IssueTransaction.typeId          -> "issue_transactions",
    TransferTransaction.typeId       -> "transfer_transactions",
    ReissueTransaction.typeId        -> "reissue_transactions",
    BurnTransaction.typeId           -> "burn_transactions",
    ExchangeTransaction.typeId       -> "exchange_transactions",
    LeaseTransaction.typeId          -> "lease_transactions",
    LeaseCancelTransaction.typeId    -> "lease_cancel_transactions",
    CreateAliasTransaction.typeId    -> "create_alias_transactions",
    MassTransferTransaction.typeId   -> "mass_transfer_transactions",
    DataTransaction.typeId           -> "data_transactions",
    SetScriptTransaction.typeId      -> "set_script_transactions",
    SponsorFeeTransaction.typeId     -> "sponsor_fee_transactions",
    SetAssetScriptTransaction.typeId -> "set_asset_script_transactions"
  )

  def whereHeight(height: Int) = {
    fr"WHERE height = $height"
  }

  def whereId(id: ByteStr) = {
    fr"WHERE id = '${id.base58}'"
  }
}

object DoobieGetInstances {
  import doobie.postgres.implicits._

  implicit val bigIntMeta: Meta[BigInt] =
    Meta[BigDecimal].imap(_.toBigInt())(BigDecimal(_))

  implicit val arrayByteMeta: Meta[Array[Byte]] =
    Meta[String].imap(s => Base58.decode(s).get)(Base58.encode)

  implicit val byteStrMeta: Meta[ByteStr] =
    Meta[String].imap(s => ByteStr.decodeBase58(s).get)(s => s.toString)

  implicit val publickKeyAccountkMeta: Meta[PublicKeyAccount] =
    Meta[String].imap(s => PublicKeyAccount.fromBase58String(s).right.get)(pka => Base58.encode(pka.publicKey))

  implicit val proofsMeta: Meta[Proofs] =
    Meta[Array[String]].imap(s => Proofs(s.map(x => ByteStr.decodeBase58(x).get)))(_.base58().toArray)

  implicit val intArray: Meta[Seq[Int]] =
    Meta[Array[Int]].imap(_.toSeq)(seq => Array.apply(seq: _*))

  implicit val scriptMeta: Meta[Script] =
    Meta[String].imap(s => Script.fromBase64String(s).right.get)(_.text)

  implicit val addressMeta: Meta[Address] =
    Meta[String].imap(s => Address.fromString(s).right.get)(_.address)

  implicit val aliasMeta: Meta[Alias] =
    Meta[String].imap(s => Alias.fromString(s).right.get)(_.stringRepr)

  implicit val addressOrAliasMeta: Meta[AddressOrAlias] =
    Meta[String].imap(s => AddressOrAlias.fromString(s).right.get)(_.stringRepr)

  val orderGet: Get[Order] = {
    import OrderJson._
    Get.Advanced.other[PGobject](NonEmptyList.of("json")).tmap { o =>
      Json.parse(o.getValue).as[Order]
    }
  }

  val orderPut: Put[Order] = {
    Put.Advanced.other[PGobject](NonEmptyList.of("json")).tcontramap[Order] { order =>
      val o = new PGobject
      o.setType("json")
      o.setValue(order.json().toString())
      o
    }
  }

  implicit val orderV1Put: Put[OrderV1] = {
    orderPut.contramap { order =>
      order.asInstanceOf[OrderV1]
    }
  }

  implicit val orderV2Put: Put[OrderV2] = {
    orderPut.contramap { order =>
      order.asInstanceOf[OrderV2]
    }
  }

  implicit val orderV1Get: Get[OrderV1] = {
    orderGet.map { order =>
      order.asInstanceOf[OrderV1]
    }
  }

  implicit val orderV2Get: Get[OrderV2] = {
    orderGet.map { order =>
      order.asInstanceOf[OrderV2]
    }
  }

  implicit val dataEntryRead: Read[DataEntry[_]] = Read[(String, String, Option[Long], Option[Boolean], Option[String], Option[String])].map {
    case (dataKey, dataType, integer, boolean, binary, string) =>
      dataType match {
        case "integer" => IntegerDataEntry(dataKey, integer.get)
        case "boolean" => BooleanDataEntry(dataKey, boolean.get)
        case "binary"  => BinaryDataEntry(dataKey, ByteStr.decodeBase58(binary.get).get)
        case "string"  => StringDataEntry(dataKey, string.get)
      }
  }

  implicit val dataEntryWrite: Write[DataEntry[_]] = {
    val nullString  = Option.empty[String]
    val nullBoolean = Option.empty[Boolean]
    val nullLong    = Option.empty[Long]
    Write[(String, String, Option[Long], Option[Boolean], Option[String], Option[String])].contramap {
      case IntegerDataEntry(key, integer) => (key, "integer", Some(integer), nullBoolean, nullString, nullString)
      case BooleanDataEntry(key, boolean) => (key, "boolean", nullLong, Some(boolean), nullString, nullString)
      case BinaryDataEntry(key, binary)   => (key, "binary", nullLong, nullBoolean, Some(binary.toString), nullString)
      case StringDataEntry(key, string)   => (key, "string", nullLong, nullBoolean, nullString, Some(string))
    }
  }
}
