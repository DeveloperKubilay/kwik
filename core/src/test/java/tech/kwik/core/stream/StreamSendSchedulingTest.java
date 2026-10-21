package tech.kwik.core.stream;

import org.junit.jupiter.api.Test;
import tech.kwik.core.common.EncryptionLevel;
import tech.kwik.core.frame.QuicFrame;
import tech.kwik.core.frame.StreamFrame;
import tech.kwik.core.impl.QuicConnectionImpl;
import tech.kwik.core.impl.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StreamSendSchedulingTest {
    @Test
    void aSenderThatRunsBeforeSubmissionReturnsDoesNotStrandBufferedBytes() throws Exception {
        QuicConnectionImpl connection = mock(QuicConnectionImpl.class);
        StreamManager manager = mock(StreamManager.class);
        when(manager.getMaxBidirectionalStreamBufferSize()).thenReturn(65_536L);
        QuicStreamImpl stream = new QuicStreamImpl(0, Role.Client, connection, manager,
            new FlowControl(Role.Client, 65_536, 65_536, 65_536, 65_536));
        List<StreamFrame> sent = new ArrayList<>();
        doAnswer(call -> {
            Function<Integer, QuicFrame> supplier = call.getArgument(0);
            QuicFrame frame = supplier.apply(1_200);
            if (frame instanceof StreamFrame) sent.add((StreamFrame) frame);
            return null;
        }).when(connection).send(any(Function.class), anyInt(), any(EncryptionLevel.class), any(Consumer.class), anyBoolean());
        stream.getOutputStream().write(new byte[4_096]);
        assertThat(sent.stream().mapToInt(StreamFrame::getLength).sum()).isEqualTo(4_096);
        stream.getOutputStream().write(new byte[4_096]);
        assertThat(sent.stream().mapToInt(StreamFrame::getLength).sum()).isEqualTo(8_192);
    }
}
