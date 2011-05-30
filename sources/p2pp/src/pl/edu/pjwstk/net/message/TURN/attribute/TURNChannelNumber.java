/**
 * 
 */
package pl.edu.pjwstk.net.message.TURN.attribute;

import pl.edu.pjwstk.net.message.TURN.TURNAttribute;
import pl.edu.pjwstk.net.message.TURN.TURNAttributeType;
import pl.edu.pjwstk.net.message.TURN.TURNMessage;
import pl.edu.pjwstk.types.ExtendedBitSet;

/**
 * @author Robert Strzelecki rstrzele@gmail.com
 * package pl.edu.pjwstk.net.message.TURN.attribute
 */
public class TURNChannelNumber extends TURNAttribute {

	public TURNChannelNumber(TURNMessage turnMessage,
			ExtendedBitSet valueEBS,
			int messagePosition) {
		super(turnMessage, TURNAttributeType.CHANNEL_NUMBER, valueEBS, messagePosition);
		// TODO Auto-generated constructor stub
	}


}
