package com.project.chat_service.events;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.mapping.event.AbstractMongoEventListener;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertEvent;
import org.springframework.stereotype.Component;

import com.project.chat_service.dto.MessageDto;
import com.project.chat_service.service.SequenceGeneratorService;

public class MessageMongoEventListener extends AbstractMongoEventListener<MessageDto> {

     private SequenceGeneratorService sequenceGenerator;

    @Autowired
    public MessageMongoEventListener(SequenceGeneratorService sequenceGenerator) {
        this.sequenceGenerator = sequenceGenerator;
    }

    @Override
    public void onBeforeConvert(BeforeConvertEvent<MessageDto> event) {
        if (Integer.parseInt(event.getSource().getId()) < 1) {
            event.getSource().setId(String.valueOf(sequenceGenerator.generateSequence(MessageDto.SEQUENCE_NAME)));
        }
    }


}
