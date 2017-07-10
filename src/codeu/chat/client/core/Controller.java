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

package codeu.chat.client.core;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.Thread;

import codeu.chat.common.BasicController;
import codeu.chat.common.ConversationHeader;
import codeu.chat.common.InterestSet;
import codeu.chat.common.Message;
import codeu.chat.common.NetworkCode;
import codeu.chat.common.User;
import codeu.chat.util.Logger;
import codeu.chat.util.Serializer;
import codeu.chat.util.Serializers;
import codeu.chat.util.Uuid;
import codeu.chat.util.connections.Connection;
import codeu.chat.util.connections.ConnectionSource;

final class Controller implements BasicController {
  
  private final static Logger.Log LOG = Logger.newLog(Controller.class);

  private final ConnectionSource source;

  public Controller(ConnectionSource source) {
    this.source = source;
  }

  @Override
  public Message newMessage(Uuid author, Uuid conversation, String body) {

    Message response = null;

    try (final Connection connection = source.connect()) {

      Serializers.INTEGER.write(connection.out(), NetworkCode.NEW_MESSAGE_REQUEST);
      Uuid.SERIALIZER.write(connection.out(), author);
      Uuid.SERIALIZER.write(connection.out(), conversation);
      Serializers.STRING.write(connection.out(), body);

      int reply = Serializers.INTEGER.read(connection.in());
      if(reply == NetworkCode.CONVERSATION_ACCESS_DENIED)
      {
        System.out.println("WARNING: Access Denied.");
      }
      else if (reply == NetworkCode.NEW_MESSAGE_RESPONSE)
      {
        response = Serializers.nullable(Message.SERIALIZER).read(connection.in());
      } 
      else 
      {
        LOG.error("Response from server failed.");
      }
    } catch (Exception ex) {
      System.out.println("ERROR: Exception during call on server. Check log for details.");
      LOG.error(ex, "Exception during call on server.");
    }

    return response;
  }

  @Override
  public User newUser(String name) {

    User response = null;

    try (final Connection connection = source.connect()) {

      Serializers.INTEGER.write(connection.out(), NetworkCode.NEW_USER_REQUEST);
      Serializers.STRING.write(connection.out(), name);
      LOG.info("newUser: Request completed.");

      if (Serializers.INTEGER.read(connection.in()) == NetworkCode.NEW_USER_RESPONSE) {
        response = Serializers.nullable(User.SERIALIZER).read(connection.in());
        LOG.info("newUser: Response completed.");
      } else {
        LOG.error("Response from server failed.");
      }
    } catch (Exception ex) {
      System.out.println("ERROR: Exception during call on server. Check log for details.");
      LOG.error(ex, "Exception during call on server.");
    }

    return response;
  }

  @Override
  public ConversationHeader newConversation(String title, Uuid owner)  {

    ConversationHeader response = null;

    try (final Connection connection = source.connect()) {

      Serializers.INTEGER.write(connection.out(), NetworkCode.NEW_CONVERSATION_REQUEST);
      Serializers.STRING.write(connection.out(), title);
      Uuid.SERIALIZER.write(connection.out(), owner);

      if (Serializers.INTEGER.read(connection.in()) == NetworkCode.NEW_CONVERSATION_RESPONSE) {
        response = Serializers.nullable(ConversationHeader.SERIALIZER).read(connection.in());
      } else {
        LOG.error("Response from server failed.");
      }
    } catch (Exception ex) {
      System.out.println("ERROR: Exception during call on server. Check log for details.");
      LOG.error(ex, "Exception during call on server.");
    }

    return response;
  }
  
  @Override
  public InterestSet getInterestSet(Uuid id){
    InterestSet interestSet = null;
    try (final Connection connection = source.connect()){
      
      Serializers.INTEGER.write(connection.out(), NetworkCode.INTEREST_SET_REQUEST);
      Uuid.SERIALIZER.write(connection.out(), id);
      
      if (Serializers.INTEGER.read(connection.in()) == NetworkCode.INTEREST_SET_RESPONSE) {
        interestSet = Serializers.nullable(InterestSet.SERIALIZER).read(connection.in());
      } else {
        LOG.error("Response from server failed.");
      }
      
    } catch (Exception ex) {
      System.out.println("ERROR: Exception during call on server. Check log for details.");
      LOG.error(ex, "Exception during call on server.");
    }
    return interestSet;
  }  
  
  @Override
  public void updateInterests(Uuid id, InterestSet intSet) {
    try (final Connection connection = this.source.connect()){
      Serializers.INTEGER.write(connection.out(), NetworkCode.INTEREST_SET_RECORD);
      Uuid.SERIALIZER.write(connection.out(), id);
      InterestSet.SERIALIZER.write(connection.out(), intSet);
    } catch (Exception ex) {
      System.out.println("ERROR: Exception during call on server. Check log for details.");
      LOG.error(ex, "Exception during call on server.");
    }
    
  }

  public void authorityModificationRequest(ConversationHeader conversation, User targetUser, User user, String parameterString)
  {
    try(final Connection connection = this.source.connect())
    {
      Serializers.INTEGER.write(connection.out(), NetworkCode.CONVERSATION_AUTHORITY_REQUEST);
      ConversationHeader.SERIALIZER.write(connection.out(), conversation);
      User.SERIALIZER.write(connection.out(), targetUser);
      User.SERIALIZER.write(connection.out(), user);
      Serializers.STRING.write(connection.out(), parameterString);

      int reply = Serializers.INTEGER.read(connection.in());

      if(reply == NetworkCode.CONVERSATION_ACCESS_DENIED)
      {
        System.out.println("WARNING: Access denied! Not enough authority!");
      }
      else if(reply == NetworkCode.CONVERSATION_AUTHORITY_RESPONSE)
      {
        System.out.println("Successfully set the authority!");
      }
      else 
      {
        LOG.error("Response from server failed.");
      }
    }
    catch(Exception ex)
    {
      System.out.println("ERROR: Exception during call on server. Check log for details.");
      LOG.error(ex, "Exception during call on server.");
    }
  }
}
