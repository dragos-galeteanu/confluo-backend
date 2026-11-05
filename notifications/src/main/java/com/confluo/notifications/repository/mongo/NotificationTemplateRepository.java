package com.confluo.notifications.repository.mongo;

import com.confluo.common.mongo.repository.BaseMongoRepository;
import com.confluo.notifications.channel.Channel;
import com.confluo.notifications.document.NotificationTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * The wording of every message, by key, channel and locale.
 *
 * <p>The resolution lookup asks for a <em>live</em> template - one that is neither disabled nor
 * soft-deleted - and says so in the method name rather than filtering afterwards, because the
 * compound index is on the three identifying fields and the two lifecycle checks ride along on the
 * document it finds. A caller that fetched a retired template and then discarded it would be a
 * caller that could forget to.</p>
 */
@Repository
public interface NotificationTemplateRepository extends BaseMongoRepository<NotificationTemplate> {

    /**
     * The live template for one key, channel and locale.
     * @param templateKey what the message is
     * @param channel the channel
     * @param locale the language tag, lowercased
     * @return the template, or empty if there is none or it has been retired
     */
    Optional<NotificationTemplate> findByTemplateKeyAndChannelAndLocaleAndDisabledAtIsNullAndDeletedAtIsNull(
            String templateKey, Channel channel, String locale);

    /**
     * Any template for one key, channel and locale, retired or not.
     *
     * <p>What the uniqueness check uses: a retired template still occupies its place in the
     * compound index, so creating another for the same three would be refused by MongoDB rather
     * than by anything that could explain itself.</p>
     *
     * @param templateKey what the message is
     * @param channel the channel
     * @param locale the language tag, lowercased
     * @return the template, or empty if nothing has ever been written for those three
     */
    Optional<NotificationTemplate> findByTemplateKeyAndChannelAndLocaleAndDeletedAtIsNull(
            String templateKey, Channel channel, String locale);
}
