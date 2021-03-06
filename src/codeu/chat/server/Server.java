// Copyright 2017 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.


package codeu.chat.server;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import codeu.chat.common.ConversationHeader;
import codeu.chat.common.ConversationHeader.ConversationUuid;
import codeu.chat.common.ConversationPayload;
import codeu.chat.common.InterestSet;
import codeu.chat.common.Message;
import codeu.chat.common.NetworkCode;
import codeu.chat.common.Relay;
import codeu.chat.common.Secret;
import codeu.chat.common.ServerInfo;
import codeu.chat.common.User;
import codeu.chat.server.LocalFile;
import codeu.chat.util.Logger;
import codeu.chat.util.Serializers;
import codeu.chat.util.Time;
import codeu.chat.util.Timeline;
import codeu.chat.util.Uuid;
import codeu.chat.util.connections.Connection;

public final class Server {
  private interface Command {
    void onMessage(InputStream in, OutputStream out) throws IOException;
  }

  private static final Logger.Log LOG = Logger.newLog(Server.class);

  private static final int RELAY_REFRESH_MS = 5000;  // 5 seconds
  private static final int LOCAL_FILE_REFRESH_MS = 1000;

  private final Timeline timeline = new Timeline();

  private final Map<Integer, Command> commands = new HashMap<>();
  
  private final Uuid id;
  private final Secret secret;
  private static final ServerInfo info = new ServerInfo();

  private final Model model = new Model();
  private final View view = new View(model);
  private final Controller controller;

  private final Relay relay;
  private Uuid lastSeen = Uuid.NULL;

  private final File file;
  private final LocalFile localFile;
  //One extra is added to this constructor, which can get the path information from user.
  public Server(final Uuid id, final Secret secret, final Relay relay,final File localFilePath) {

    this.id = id;
    this.secret = secret;
    this.file = localFilePath;
    this.localFile = new LocalFile(new File(file.getPath()));//file path is given by user
    this.controller = new Controller(id, model,localFile);//Use the new constructor to create this new controller.
    this.relay = relay;
    this.commands.put(NetworkCode.CONVERSATION_AUTHORITY_REQUEST, new Command()
    {
      @Override
      public void onMessage(InputStream in, OutputStream out) throws IOException
      {
        final ConversationUuid conversation = new ConversationUuid(Uuid.SERIALIZER.read(in));
        final Uuid targetUser = Uuid.SERIALIZER.read(in);
        final Uuid fromUser = Uuid.SERIALIZER.read(in);
        final String parameterString = Serializers.STRING.read(in);
        if(fromUser.equals(targetUser))
        {
          Serializers.INTEGER.write(out, NetworkCode.CONVERSATION_ACCESS_DENIED);
        }
        else if(!model.isMember(conversation, fromUser))
        {
           Serializers.INTEGER.write(out, NetworkCode.CONVERSATION_ACCESS_DENIED);
        }
        else if(!model.isOwner(conversation, fromUser) && !model.isCreator(conversation, fromUser))
        {
          Serializers.INTEGER.write(out, NetworkCode.CONVERSATION_ACCESS_DENIED);
        }
        else if(model.isOwner(conversation, fromUser) && parameterString.equals("o"))
        {
          Serializers.INTEGER.write(out, NetworkCode.CONVERSATION_ACCESS_DENIED);
        }
        else if(model.isOwner(conversation, fromUser) && model.isOwner(conversation, targetUser))
        {
          Serializers.INTEGER.write(out, NetworkCode.CONVERSATION_ACCESS_DENIED);
        }
        else if(model.isOwner(conversation, fromUser) && model.isCreator(conversation, targetUser))
        {
          Serializers.INTEGER.write(out, NetworkCode.CONVERSATION_ACCESS_DENIED);
        }
        else
        {
          controller.authorityModificationRequest(conversation, targetUser, fromUser, parameterString);
          
          Serializers.INTEGER.write(out, NetworkCode.CONVERSATION_AUTHORITY_RESPONSE);
        }
      }
    });
    // New Message - A client wants to add a new message to the back end.
    this.commands.put(NetworkCode.NEW_MESSAGE_REQUEST, new Command() {
      @Override
      public void onMessage(InputStream in, OutputStream out) throws IOException {

        final Uuid author = Uuid.SERIALIZER.read(in);
        final ConversationUuid conversation = new ConversationUuid( Uuid.SERIALIZER.read(in));
        final String content = Serializers.STRING.read(in);

        if(!model.isMember(conversation, author)){
          Serializers.INTEGER.write(out, NetworkCode.CONVERSATION_ACCESS_DENIED);
        } else {
          final Message message = controller.newMessage(author, conversation, content);
          Serializers.INTEGER.write(out, NetworkCode.NEW_MESSAGE_RESPONSE);
          Serializers.nullable(Message.SERIALIZER).write(out, message);
          timeline.scheduleNow(createSendToRelayEvent(
        	author,
        	conversation,
        	message.id));
        }
      }
    });
      
    // New User - A client wants to add a new user to the back end.
    this.commands.put(NetworkCode.NEW_USER_REQUEST,  new Command() {
      @Override
      public void onMessage(InputStream in, OutputStream out) throws IOException {

        final String name = Serializers.STRING.read(in);
        final User user = controller.newUser(name);
        
        Serializers.INTEGER.write(out, NetworkCode.NEW_USER_RESPONSE);
        Serializers.nullable(User.SERIALIZER).write(out, user);
      }
    });

    // New Conversation - A client wants to add a new conversation to the back end.
    this.commands.put(NetworkCode.NEW_CONVERSATION_REQUEST,  new Command() {
      @Override
      public void onMessage(InputStream in, OutputStream out) throws IOException {

        final String title = Serializers.STRING.read(in);
        final Uuid owner = Uuid.SERIALIZER.read(in);
        final ConversationHeader conversation = controller.newConversation(title, owner);

        Serializers.INTEGER.write(out, NetworkCode.NEW_CONVERSATION_RESPONSE);
        Serializers.nullable(ConversationHeader.SERIALIZER).write(out, conversation);
      }
    });

    // Get Users - A client wants to get all the users from the back end.
    this.commands.put(NetworkCode.GET_USERS_REQUEST, new Command() {
      @Override
      public void onMessage(InputStream in, OutputStream out) throws IOException {

        final Collection<User> users = view.getUsers();

        Serializers.INTEGER.write(out, NetworkCode.GET_USERS_RESPONSE);
        Serializers.collection(User.SERIALIZER).write(out, users);
      }
    });

    // Get Conversations - A client wants to get all the conversations from the back end.
    this.commands.put(NetworkCode.GET_ALL_CONVERSATIONS_REQUEST, new Command() {
      @Override
      public void onMessage(InputStream in, OutputStream out) throws IOException {

        final Collection<ConversationHeader> conversations = view.getConversations();

        Serializers.INTEGER.write(out, NetworkCode.GET_ALL_CONVERSATIONS_RESPONSE);
        Serializers.collection(ConversationHeader.SERIALIZER).write(out, conversations);
      }
    });

    // Get Conversations By Id - A client wants to get a subset of the converations from
    //                           the back end. Normally this will be done after calling
    //                           Get Conversations to get all the headers and now the client
    //                           wants to get a subset of the payloads.
    this.commands.put(NetworkCode.GET_CONVERSATIONS_BY_ID_REQUEST, new Command() {
      @Override
      public void onMessage(InputStream in, OutputStream out) throws IOException {

        final Collection<Uuid> ids = Serializers.collection(Uuid.SERIALIZER).read(in);
        final Collection<ConversationPayload> conversations = view.getConversationPayloads(ids);

        Serializers.INTEGER.write(out, NetworkCode.GET_CONVERSATIONS_BY_ID_RESPONSE);
        Serializers.collection(ConversationPayload.SERIALIZER).write(out, conversations);
      }
    });

    // Get Messages By Id - A client wants to get a subset of the messages from the back end.
    this.commands.put(NetworkCode.GET_MESSAGES_BY_ID_REQUEST, new Command() {
      boolean firstCall = true;
      @Override
      public void onMessage(InputStream in, OutputStream out) throws IOException {
    	final ConversationUuid conversation = new ConversationUuid(Uuid.SERIALIZER.read(in));
    	final Uuid user = Uuid.SERIALIZER.read(in);
      final Collection<Uuid> ids = Serializers.collection(Uuid.SERIALIZER).read(in);
          if(firstCall && !model.isMember(conversation, user)){
          Serializers.INTEGER.write(out, NetworkCode.CONVERSATION_ACCESS_DENIED);
        } else {
          firstCall = false;
          final Collection<Message> messages = view.getMessages(conversation, user, ids);
          if(messages.size() == 0)
          {
            firstCall = true;
          }
          Serializers.INTEGER.write(out, NetworkCode.GET_MESSAGES_BY_ID_RESPONSE);
          Serializers.collection(Message.SERIALIZER).write(out, messages);
        }     
      }
    });

    //Get the version from server
    this.commands.put(NetworkCode.SERVER_INFO_REQUEST, new Command()
    {
      @Override
      public void onMessage(InputStream in,OutputStream out) throws IOException
      {
        Serializers.INTEGER.write(out, NetworkCode.SERVER_INFO_RESPONSE);
        Uuid.SERIALIZER.write(out, info.version);
        Time.SERIALIZER.write(out, view.getInfo().startTime);
      }
    });

    this.commands.put(NetworkCode.INTEREST_SET_REQUEST, new Command()
    {
      @Override
      public void onMessage(InputStream in,OutputStream out) throws IOException
      {
        final Uuid id = Uuid.SERIALIZER.read(in);
       
        Serializers.INTEGER.write(out, NetworkCode.INTEREST_SET_RESPONSE);
        InterestSet.SERIALIZER.write(out, model.getInterestSet(id)); 
      }
      
    });
    
    this.commands.put(NetworkCode.INTEREST_SET_RECORD, new Command()
    {
      @Override
      public void onMessage(InputStream in,OutputStream out) throws IOException
      {
        final Uuid id = Uuid.SERIALIZER.read(in);
        final InterestSet intSet = InterestSet.SERIALIZER.read(in);
        controller.updateInterests(id, intSet);
      }
    });

    this.timeline.scheduleNow(new Runnable() {
      @Override
      public void run() {
        try {

          LOG.info("Reading update from relay...");

          for (final Relay.Bundle bundle : relay.read(id, secret, lastSeen, 32)) {
            onBundle(bundle);
            lastSeen = bundle.id();
          }

        } catch (Exception ex) {

          LOG.error(ex, "Failed to read update from relay.");

        }

        timeline.scheduleIn(RELAY_REFRESH_MS, this);
      }
    });
    //Save the data periodically
    this.timeline.scheduleNow(new Runnable() {
      @Override
      public void run() 
      {
        try
        {
          localFile.saveData();
        }
        catch(IOException exception)
        {
          System.out.println("ERROR:Failed to store file!");
          exception.printStackTrace();
        }
        finally
        {
          timeline.scheduleIn(LOCAL_FILE_REFRESH_MS, this);
        }
      }
    });
  }
  
  public void handleConnection(final Connection connection) {
    timeline.scheduleNow(new Runnable() {
      @Override
      public void run() {
        try {

          LOG.info("Handling connection...");

          final int type = Serializers.INTEGER.read(connection.in());
          final Command command = commands.get(type);

          if (command == null) {
            // The message type cannot be handled so return a dummy message.
            Serializers.INTEGER.write(connection.out(), NetworkCode.NO_MESSAGE);
            LOG.info("Connection rejected");
          } else {
            command.onMessage(connection.in(), connection.out());
            LOG.info("Connection accepted");
          }

        } catch (Exception ex) {

          LOG.error(ex, "Exception while handling connection.");

        }

        try {
          connection.close();
        } catch (Exception ex) {
          LOG.error(ex, "Exception while closing connection.");
        }
      }
    });
  }

  private void onBundle(Relay.Bundle bundle) {

    final Relay.Bundle.Component relayUser = bundle.user();
    final Relay.Bundle.Component relayConversation = bundle.conversation();
    final Relay.Bundle.Component relayMessage = bundle.user();

    User user = model.userById().first(relayUser.id());

    if (user == null) {
      user = controller.newUser(relayUser.id(), relayUser.text(), relayUser.time());
    }

    ConversationHeader conversation = model.conversationById().first(relayConversation.id());

    if (conversation == null) {

      // As the relay does not tell us who made the conversation - the first person who
      // has a message in the conversation will get ownership over this server's copy
      // of the conversation.
      conversation = controller.newConversation((ConversationUuid) relayConversation.id(),
                                                relayConversation.text(),
                                                user.id,
                                                relayConversation.time());
    }

    Message message = model.messageById().first(relayMessage.id());

    if (message == null) {
      message = controller.newMessage(relayMessage.id(),
                                      user.id,
                                      conversation.id,
                                      relayMessage.text(),
                                      relayMessage.time());
    }
  }

  private Runnable createSendToRelayEvent(final Uuid userId,
                                          final Uuid conversationId,
                                          final Uuid messageId) {
    return new Runnable() {
      @Override
      public void run() {
        final User user = view.findUser(userId);
        final ConversationHeader conversation = view.findConversation(conversationId);
        final Message message = view.findMessage(messageId);
        relay.write(id,
                    secret,
                    relay.pack(user.id, user.name, user.creation),
                    relay.pack(conversation.id, conversation.title, conversation.creation),
                    relay.pack(message.id, message.content, message.creation));
      }
    };
  }
}
