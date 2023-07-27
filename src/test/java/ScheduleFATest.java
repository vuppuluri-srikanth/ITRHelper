import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JSR310Module;
import org.example.dtos.DtlsForeignEquityDebtInterest;
import org.example.dtos.ScheduleFA;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import java.time.LocalDate;
import java.util.Arrays;

public class ScheduleFATest {

    @Test
    void testSerialization() throws JsonProcessingException {
        DtlsForeignEquityDebtInterest dtlsForeignEquityDebtInterest = new DtlsForeignEquityDebtInterest(120, 100, LocalDate.now().toString(), 200, 10, 70);
        ScheduleFA scheduleFA = new ScheduleFA(Arrays.asList(dtlsForeignEquityDebtInterest));

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setPropertyNamingStrategy(PropertyNamingStrategy.PASCAL_CASE_TO_CAMEL_CASE);
        objectMapper.registerModule(new JSR310Module());
        String json = objectMapper.writeValueAsString(scheduleFA);
        System.out.println("json = " + json);
    }
}
